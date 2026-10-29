package com.dpadtv.client

import org.json.JSONObject
import org.json.JSONException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class CmsSourceReport(
    val name: String, val host: String, val ok: Boolean, val count: Int,
    val pages: Int, val failedPages: List<Int>, val ms: Long, val category: String = "", val error: String = "",
)

class AppleCmsClient {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
    var lastReports: List<CmsSourceReport> = emptyList()
        private set

    private data class Outcome(val items: List<SearchItem>, val report: CmsSourceReport, val lastPage: Int = 0, val finished: Boolean = true)

    fun search(
        keyword: String,
        sources: List<SourceConfig>,
        deadlineNanos: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(12),
        onProgress: ((SearchResult) -> Unit)? = null,
    ): SearchResult {
        val selected = sources.filter { it.enabled && !it.isAdult }
        if (selected.isEmpty()) { lastReports = emptyList(); return SearchResult(emptyList(), 0) }
        val pool = Executors.newFixedThreadPool(minOf(8, selected.size))
        val updates = LinkedBlockingQueue<Pair<Int, Outcome>>()
        val outcomes = arrayOfNulls<Outcome>(selected.size)
        val started = System.nanoTime()
        try {
            selected.forEachIndexed { index, source ->
                pool.submit { searchSource(source, keyword, deadlineNanos) { updates.offer(index to it) } }
            }
            var finished = 0
            while (finished < selected.size) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) break
                val (index, update) = updates.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                outcomes[index] = update
                if (update.finished) finished++
                onProgress?.invoke(result(outcomes.filterNotNull(), keyword))
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val collected = selected.mapIndexed { index, source ->
                val saved = outcomes[index]
                when {
                    saved == null -> Outcome(emptyList(), CmsSourceReport(source.name, "", false, 0, 0, emptyList(), elapsed, "TIMEOUT", "搜索总时限已到"))
                    saved.finished -> saved
                    else -> saved.copy(report = saved.report.copy(
                        failedPages = saved.report.failedPages + ((saved.lastPage + 1)..saved.report.pages),
                        ms = elapsed, category = "PARTIAL", error = "后续页面超时，已保留成功结果"))
                }
            }
            lastReports = collected.map { it.report }
            return result(collected, keyword)
        } finally { pool.shutdownNow() }
    }

    private fun result(outcomes: List<Outcome>, keyword: String): SearchResult {
        val unique = outcomes.flatMap { it.items }.distinctBy { it.sourceUrl + '\u0000' + it.vodId }.filterNot(AppleCmsParser::isAdult)
        val exact = AppleCmsParser.normalize(keyword)
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        val items = AppleCmsParser.relevant(unique, keyword).sortedWith { a, b ->
            val exactOrder = (if (AppleCmsParser.normalize(a.name) == exact) 0 else 1).compareTo(if (AppleCmsParser.normalize(b.name) == exact) 0 else 1)
            if (exactOrder != 0) exactOrder else {
                val nameOrder = collator.compare(a.name, b.name)
                if (nameOrder != 0) nameOrder else collator.compare(a.sourceName, b.sourceName)
            }
        }
        val reports = outcomes.map { it.report }
        return SearchResult(items, reports.count { !it.ok }, reports)
    }

    private fun searchSource(source: SourceConfig, keyword: String, searchDeadlineNanos: Long, emit: (Outcome) -> Unit) {
        val started = System.nanoTime()
        val deadline = minOf(started + TimeUnit.SECONDS.toNanos(10), searchDeadlineNanos)
        var host = ""
        try {
            val endpoint = endpoint(source)
            host = endpoint.host
            val first = AppleCmsParser.searchPage(read(endpoint, mapOf("ac" to "videolist", "wd" to keyword, "pg" to "1"), deadline), source)
            val items = first.items.toMutableList()
            val failedPages = mutableListOf<Int>()
            val pageErrors = mutableListOf<String>()
            fun publish(page: Int) {
                emit(Outcome(items.toList(), CmsSourceReport(source.name, host, true, items.size, first.pageCount, failedPages.toList(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), if (failedPages.isEmpty()) "" else "PARTIAL", pageErrors.joinToString("; ")),
                    page, page == first.pageCount))
            }
            publish(1)
            for (page in 2..first.pageCount) {
                if (Thread.currentThread().isInterrupted) return
                try {
                    items += AppleCmsParser.searchPage(read(endpoint, mapOf("ac" to "videolist", "wd" to keyword, "pg" to page.toString()), deadline), source).items
                } catch (_: InterruptedException) {
                    return
                } catch (e: Exception) {
                    failedPages += page
                    pageErrors += "第${page}页 ${failure(e).second}"
                }
                publish(page)
            }
        } catch (_: InterruptedException) {
            return
        } catch (e: Exception) {
            val (category, message) = failure(e)
            emit(Outcome(emptyList(), CmsSourceReport(source.name, host, false, 0, 0, emptyList(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), category, message)))
        }
    }

    fun detail(id: String, source: SourceConfig): Detail {
        val detail = AppleCmsParser.detail(read(endpoint(source), mapOf("ac" to "videolist", "ids" to id), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)))
        check(detail.episodes.isNotEmpty()) { "该源没有返回可直接播放的地址，请尝试其他片源" }
        return detail
    }

    fun testSource(url: String): Pair<Boolean, Int> {
        val source = SourceConfig("test", url)
        val page = AppleCmsParser.searchPage(read(endpoint(source), mapOf("ac" to "videolist", "pg" to "1"), System.nanoTime() + TimeUnit.SECONDS.toNanos(10)), source)
        return true to page.items.size
    }

    private fun endpoint(source: SourceConfig): URI = URI(source.url).also {
        require(it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty()) { "片源地址必须是完整的 HTTP 或 HTTPS URL" }
    }

    private fun read(endpoint: URI, params: Map<String, String>, deadline: Long): JSONObject {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw SocketTimeoutException("源请求超时")
        val address = endpoint.toASCIIString().substringBefore('#')
        val query = params.entries.joinToString("&") { (key, value) -> URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8") }
        val connection = directConnection(URL(address + (if ('?' in address) "&" else "?") + query), deadline, USER_AGENT, "application/json")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw HttpError(status, "")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("搜索已取消")
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) throw SocketTimeoutException("源请求超时")
                    connection.readTimeout = minOf(8000, left.coerceAtLeast(1).toInt())
                    val count = reader.read(buffer)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("搜索已取消")
                    if (System.nanoTime() >= deadline) throw SocketTimeoutException("源请求超时")
                    if (count < 0) break
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun failure(error: Exception): Pair<String, String> = when (error) {
        is HttpError -> "HTTP" to "HTTP ${error.status}"
        is UnknownHostException -> "DNS" to "域名解析失败"
        is SSLException -> "TLS" to error.javaClass.simpleName
        is SocketTimeoutException -> "TIMEOUT" to "请求超时"
        is JSONException, is IllegalArgumentException -> "PARSE" to "片源响应或地址格式无效"
        else -> "NETWORK" to error.javaClass.simpleName
    }
}
