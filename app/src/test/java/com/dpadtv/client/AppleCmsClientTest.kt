package com.dpadtv.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppleCmsClientTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var serverWorkers: ExecutorService
    private val requests = Collections.synchronizedList(mutableListOf<URI>())
    private val referers = Collections.synchronizedList(mutableListOf<String>())

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverWorkers = Executors.newCachedThreadPool()
        server.executor = serverWorkers
        base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            val uri = exchange.requestURI
            requests.add(uri)
            referers.add(exchange.requestHeaders.getFirst("Referer").orEmpty())
            val query = uri.rawQuery.orEmpty().split('&').associate { value ->
                val parts = value.split('=', limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            val page = query["pg"]?.toInt() ?: 1
            val status = if (uri.path == "/broken" || (uri.path == "/partial" && page == 2)) 503 else 200
            val row = JSONObject().put("vod_id", if (uri.path in listOf("/paged", "/partial")) page else 7)
                .put("vod_name", "三体 $page").put("vod_pic", "https://img.test/a.jpg")
                .put("type_name", "国产剧").put("vod_year", "2023")
                .put("vod_play_url", "第1集\$https://cdn.test/1.m3u8")
            val pageCount = when (uri.path) { "/paged" -> 9; "/partial" -> 3; else -> 1 }
            val json = JSONObject().put("list", JSONArray().put(row)).put("pagecount", pageCount).toString()
            val body = when {
                status != 200 -> "maintenance"
                uri.path == "/invalid" -> "<html>not JSON</html>"
                uri.path == "/recommend" -> """{"subjects":[{"title":"测试电影","cover":"https://img.test/poster.jpg","rate":"7.8"},{"title":"待评分","cover":"","rate":"0"}]}"""
                else -> json
            }.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun stopServer() { server.stop(0); serverWorkers.shutdownNow() }

    @Test fun sendsEncodedQueryDirectlyAndPreservesExistingParameters() {
        val result = AppleCmsClient().search("三体 1", listOf(SourceConfig("source", "$base/ok?token=keep")))
        assertEquals(1, result.items.size)
        assertEquals("7", result.items.single().vodId)
        val raw = requests.single().rawQuery
        assertTrue(raw.contains("token=keep"))
        assertTrue(raw.contains("wd=%E4%B8%89%E4%BD%93+1"))
        assertTrue(raw.contains("ac=videolist"))
    }

    @Test fun oneHttpFailureDoesNotDiscardAnotherSource() {
        val client = AppleCmsClient()
        val result = client.search("三体", listOf(SourceConfig("ok", "$base/ok"), SourceConfig("bad", "$base/broken")))
        assertEquals(1, result.items.size)
        assertEquals(1, result.failures)
        assertEquals("HTTP", client.lastReports.single { !it.ok }.category)
    }

    @Test fun detailUsesCmsEndpointAndExtractsDirectMedia() {
        val result = AppleCmsClient().detail("7", SourceConfig("details", "$base/details"))
        assertEquals(listOf("https://cdn.test/1.m3u8"), result.episodes)
        assertTrue(requests.single().rawQuery.contains("ids=7"))
        assertFalse(requests.single().path.contains("/api/detail"))
    }

    @Test fun fetchesNoMoreThanFiveRealPages() {
        val client = AppleCmsClient()
        val result = client.search("三体", listOf(SourceConfig("paged", "$base/paged")))
        assertEquals(5, result.items.size)
        assertEquals(5, requests.size)
        assertEquals(5, client.lastReports.single().pages)
    }

    @Test fun recordsIncompletePaginationAndKeepsSuccessfulPages() {
        val client = AppleCmsClient()
        val result = client.search("三体", listOf(SourceConfig("partial", "$base/partial")))
        assertEquals(listOf("1", "3"), result.items.map { it.vodId }.sorted())
        assertEquals(listOf(2), client.lastReports.single().failedPages)
    }

    @Test fun classifiesMalformedSourceBodyAsParseFailure() {
        val client = AppleCmsClient()
        val result = client.search("三体", listOf(SourceConfig("invalid", "$base/invalid")))
        assertEquals(1, result.failures)
        assertTrue(result.items.isEmpty())
        assertEquals("PARSE", client.lastReports.single().category)
    }

    @Test fun imageTransportUsesTheOriginalUrl() {
        assertNotNull(Api().imageBytes("$base/ok"))
        assertEquals("/ok", requests.single().path)
    }

    @Test fun failedImageIsAnUnavailableCoverRatherThanAnUncaughtTaskError() {
        assertNull(Api().imageBytes("$base/broken"))
    }

    @Test fun followsRelativeRedirectsForCmsQueries() {
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/ok?" + exchange.requestURI.rawQuery)
            exchange.sendResponseHeaders(301, -1)
            exchange.close()
        }
        val result = AppleCmsClient().search("三体", listOf(SourceConfig("redirect", "$base/redirect")))
        assertEquals(1, result.items.size)
        assertTrue(requests.single().rawQuery.contains("ac=videolist"))
    }

    @Test fun boundsRedirectLoops() {
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        assertThrows(ProtocolException::class.java) {
            directConnection(URL("$base/loop"), System.nanoTime() + TimeUnit.SECONDS.toNanos(5), "test", "application/json")
        }
    }

    @Test fun remainingDeadlineLimitsWaitingForHeaders() {
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            exchange.close()
        }
        assertThrows(SocketTimeoutException::class.java) {
            directConnection(URL("$base/slow"), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50), "test", "application/json")
        }
    }

    @Test(timeout = 4_000) fun apiRetriesShareTheCallersAbsoluteDeadline() {
        val releaseRetry = CountDownLatch(1)
        val retryStarted = CountDownLatch(1)
        server.createContext("/budget") { exchange ->
            if (exchange.requestURI.rawQuery.contains("wd=cleaned")) {
                retryStarted.countDown()
                releaseRetry.await(2, TimeUnit.SECONDS)
            } else Thread.sleep(150)
            val body = """{"list":[],"pagecount":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            val api = Api()
            val sources = listOf(SourceConfig("budget", "$base/budget"))
            val started = System.nanoTime()
            val deadline = started + TimeUnit.MILLISECONDS.toNanos(700)
            val first = api.search("original", sources, deadline)
            assertTrue(first.items.isEmpty())
            assertTrue(first.reports.single().ok)
            val retry = api.search("cleaned", sources, deadline)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(0L, retryStarted.count)
            assertEquals(1, retry.failures)
            assertEquals("TIMEOUT", retry.reports.single().category)
            assertTrue("retry restarted the budget: ${elapsed}ms", elapsed < 1_500)
        } finally { releaseRetry.countDown() }
    }

    @Test fun recommendationsUseDirectUpstreamQueryAndPublicReferer() {
        val result = Api(doubanEndpoint = "$base/recommend").douban("movie", "热门", 48, 48)
        assertEquals(listOf("测试电影", "待评分"), result.map { it.title })
        assertEquals(listOf("7.8", ""), result.map { it.rating })
        assertEquals("https://img.test/poster.jpg", result.first().cover)
        val uri = requests.single()
        assertEquals("/recommend", uri.path)
        for (parameter in listOf("type=movie", "tag=%E7%83%AD%E9%97%A8", "sort=recommend", "page_start=48", "page_limit=48")) {
            assertTrue(uri.rawQuery.contains(parameter))
        }
        assertEquals("https://movie.douban.com/", referers.single())
    }

    @Test fun varietyUsesTvEndpointRatherThanMovie() {
        Api(doubanEndpoint = "$base/recommend").douban("show", "综艺", 0, 10)
        val query = requests.single().rawQuery
        assertTrue(query.contains("type=tv"))
        assertTrue(query.contains("tag=%E7%BB%BC%E8%89%BA"))
    }

    @Test fun recommendationHttpFailuresRemainExplicit() {
        val error = assertThrows(HttpError::class.java) {
            Api(doubanEndpoint = "$base/broken").douban("tv", "热门", 0, 10)
        }
        assertEquals(503, error.status)
    }

    @Test fun missingRecommendationArrayIsNotPretendedToBeAnEmptySuccess() {
        assertThrows(org.json.JSONException::class.java) {
            Api(doubanEndpoint = "$base/ok").douban("movie", "热门", 0, 10)
        }
    }

    @Test fun publishesFastResultsBeforeWaitingForOtherSources() {
        val releaseSlow = CountDownLatch(1)
        server.createContext("/wait") { exchange ->
            releaseSlow.await(4, TimeUnit.SECONDS)
            val body = """{"list":[{"vod_id":8,"vod_name":"三体"}],"pagecount":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        var sawFastBeforeSlow = false
        val result = AppleCmsClient().search("三体", listOf(SourceConfig("slow", "$base/wait"), SourceConfig("fast", "$base/ok"))) { partial ->
            if (partial.items.any { it.sourceName == "fast" } && partial.items.none { it.sourceName == "slow" }) {
                sawFastBeforeSlow = true
                releaseSlow.countDown()
            }
        }
        assertTrue("fast results must not wait for the slow source", sawFastBeforeSlow)
        assertEquals(2, result.items.size)
    }

    @Test fun publishedPageSnapshotDoesNotChangeAsLaterPagesArrive() {
        var firstItems: List<SearchItem>? = null
        val result = AppleCmsClient().search("三体", listOf(SourceConfig("pages", "$base/paged"))) { partial ->
            if (firstItems == null && partial.items.isNotEmpty()) firstItems = partial.items
        }
        assertNotNull(firstItems)
        assertEquals(1, firstItems!!.size)
        assertEquals(5, result.items.size)
    }

    @Test(timeout = 16000) fun slowBodyOnLaterPageKeepsTheSuccessfulFirstPage() {
        server.createContext("/trickle") { exchange ->
            val second = exchange.requestURI.rawQuery.contains("pg=2")
            val body = """{"list":[{"vod_id":1,"vod_name":"三体"}],"pagecount":2}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output ->
                for (b in body) {
                    output.write(b.toInt()); output.flush()
                    if (second) Thread.sleep(300)
                }
            }
        }
        val client = AppleCmsClient()
        val result = client.search("三体", listOf(SourceConfig("trickle", "$base/trickle")))
        assertEquals("page one must survive a later page timeout", 1, result.items.size)
        assertEquals(listOf(2), client.lastReports.single().failedPages)
        assertEquals("PARTIAL", client.lastReports.single().category)
    }

    @Test fun cancellingSearchDoesNotStartAnotherPage() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        server.createContext("/cancel-pages") { exchange ->
            if (exchange.requestURI.rawQuery.contains("pg=2")) secondRequested.countDown()
            else { firstStarted.countDown(); releaseFirst.await(3, TimeUnit.SECONDS) }
            val body = """{"list":[{"vod_id":1,"vod_name":"三体"}],"pagecount":2}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val caller = Executors.newSingleThreadExecutor()
        try {
            val task = caller.submit<SearchResult> { AppleCmsClient().search("三体", listOf(SourceConfig("cancel", "$base/cancel-pages"))) }
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS))
            assertTrue(task.cancel(true))
            releaseFirst.countDown()
            assertFalse("cancelled workers must not continue paging", secondRequested.await(700, TimeUnit.MILLISECONDS))
        } finally { releaseFirst.countDown(); caller.shutdownNow() }
    }
}
