package com.dpadtv.client

import android.app.Instrumentation
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import org.json.JSONObject
import java.io.InterruptedIOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real Media3 cache reads against a device-local HLS/progressive fixture, without decoding. */
class NextEpisodeCacheProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        val releaseSecond = CountDownLatch(1)
        val requestedSecond = CountDownLatch(1)
        val segment = ByteArray(128 * 1024) { (it % 251).toByte() }
        val large = if (caseId == "next-episode-limit") ByteArray(5 * 1024 * 1024) { 31 } else segment
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6,
            first.ts
            #EXTINF:6,
            second.ts
            #EXTINF:6,
            third.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            selected.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            unused.m3u8
        """.trimIndent().toByteArray()
        val server = RegressionHttpServer { uri ->
            when (uri.path) {
                "/master.m3u8" -> master
                "/selected.m3u8", "/unused.m3u8" -> playlist
                "/second.ts" -> {
                    if (caseId == "next-episode-cancel") { requestedSecond.countDown(); releaseSecond.await() }
                    segment
                }
                "/large.mp4" -> large
                else -> segment
            }
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent("DpadTV-Android")
            .setConnectTimeoutMs(3_000).setReadTimeoutMs(3_000)
        val cache = PlaybackCache.get(context)
        val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
        val executor = Executors.newSingleThreadExecutor()
        try {
            // Reflection lets the baseline APK fail on the missing implementation before it is added.
            val type = Class.forName("com.dpadtv.client.NextEpisodePrefetcher")
            val worker = type.getConstructor(CacheDataSource.Factory::class.java, DataSource.Factory::class.java)
                .newInstance(factory, http)
            val cacheMethod = type.getDeclaredMethod("cache", String::class.java, Int::class.javaPrimitiveType)
            fun warm(url: String) {
                try { cacheMethod.invoke(worker, url, 720) }
                catch (error: InvocationTargetException) { throw error.targetException }
            }
            if (caseId == "next-episode-limit") {
                val url = server.base + "/large.mp4"
                warm(url)
                val bytes = cache.getCachedBytes(url, 0, large.size.toLong())
                check(bytes == 4 * 1024 * 1024L) { "Progressive warm-up exceeded or missed its 4 MiB bound: $bytes" }
                return JSONObject().put("case", caseId).put("cachedBytes", bytes).put("inputBytes", large.size)
            }
            if (caseId == "next-episode-cancel") {
                val future = executor.submit { warm(server.base + "/selected.m3u8") }
                check(requestedSecond.await(5, TimeUnit.SECONDS)) { "Second segment was not requested" }
                type.getDeclaredMethod("cancel").invoke(worker)
                releaseSecond.countDown()
                try {
                    future.get(5, TimeUnit.SECONDS)
                    error("Cancelled warm-up completed normally")
                } catch (error: ExecutionException) {
                    check(error.cause is InterruptedIOException) { "Unexpected cancellation result: ${error.cause}" }
                }
                check(server.count("/third.ts") == 0) { "Cancelled warm-up fetched another segment" }
                return JSONObject().put("case", caseId).put("cancelled", true).put("thirdSegmentRequests", 0)
            }
            warm(server.base + "/master.m3u8")
            check(server.count("/selected.m3u8") == 1 && server.count("/unused.m3u8") == 0)
            check(server.count("/first.ts") == 1 && server.count("/second.ts") == 1 && server.count("/third.ts") == 0) {
                "HLS warm-up did not stop after the first 12 seconds"
            }
            for (path in listOf("/first.ts", "/second.ts")) {
                val actual = DataSourceInputStream(factory.createDataSource(),
                    DataSpec.Builder().setUri(server.base + path).build()).use { it.readBytes() }
                check(actual.contentEquals(segment)) { "Cached segment bytes changed" }
                check(server.count(path) == 1) { "Normal playback data source downloaded warmed bytes again: $path" }
            }
            return JSONObject().put("case", caseId).put("cachedSegments", 2).put("durationSeconds", 12)
                .put("normalDataSourceCacheHit", true).put("unusedVariantRequests", 0).put("thirdSegmentRequests", 0)
        } finally {
            releaseSecond.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            listOf("/master.m3u8", "/selected.m3u8", "/unused.m3u8", "/first.ts", "/second.ts", "/third.ts", "/large.mp4")
                .forEach { cache.removeResource(server.base + it) }
            server.close()
            check(before == preferences.all) { "Next-episode cache fixture changed preferences" }
        }
    }
}
