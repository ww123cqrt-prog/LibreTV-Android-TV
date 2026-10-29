package com.dpadtv.client

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Test-APK-only probe. Runs the same Api/client used by the TV UI on the projector. */
class NativeCmsInstrumentation : Instrumentation() {
    private var options = Bundle()

    override fun onCreate(arguments: Bundle?) {
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val report = JSONObject().put("package", targetContext.packageName)
            .put("query", options.getString("query", "三体"))
            .put("backend", "none; Kotlin direct Apple CMS requests")
        val started = System.nanoTime()
        try {
            if (options.getString("mode") == "regression") {
                val caseId = options.getString("case").orEmpty()
                val result = when (caseId) {
                    "r1", "r1-seek", "r7" -> MainHistoryProbe(this).run(caseId)
                    "r2", "r3" -> PlaybackLifecycleProbe(this).run(caseId)
                    "r4" -> SourcePickerProbe(this).run()
                    "r4-unknown" -> SourcePickerProbe(this).run(unknownYear = true)
                    "r5" -> SearchBudgetProbe(this).run()
                    "r8" -> ImageLoadProbe(this).run()
                    else -> error("Unknown regression case: $caseId")
                }
                report.put("regression", result).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                finish(0, Bundle().apply { putString("report", report.toString()) })
                return
            }
            if (options.getString("mode") == "switch-player") {
                report.put("switching", PlaybackSwitchProbe(this).run())
                    .put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                finish(0, Bundle().apply { putString("report", report.toString()) })
                return
            }
            val api = Api()
            if (options.getString("mode") == "recommend") {
                report.put("backend", "none; Kotlin direct Douban requests")
                val batches = JSONArray()
                val categories = options.getString("tag")?.let { listOf(options.getString("type", "movie")!! to it) }
                    ?: listOf("movie" to "热门", "tv" to "热门", "show" to "综艺")
                for ((type, tag) in categories) {
                    val entry = JSONObject().put("type", type).put("tag", tag)
                    val requestStarted = System.nanoTime()
                    try {
                        val items = api.douban(type, tag, 0, 48)
                        entry.put("ok", true).put("count", items.size).put("titles", JSONArray(items.take(6).map { it.title }))
                        val cover = items.firstOrNull()?.cover.orEmpty()
                        entry.put("coverHost", if (cover.isEmpty()) "" else URI(cover).host)
                        val bytes = if (cover.isEmpty()) null else api.imageBytes(cover)
                        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        entry.put("coverBytes", bytes?.size ?: 0).put("coverDecoded", bitmap != null)
                            .put("coverWidth", bitmap?.width ?: 0).put("coverHeight", bitmap?.height ?: 0)
                        bitmap?.recycle()
                    } catch (error: Exception) {
                        entry.put("ok", false).put("error", error.javaClass.simpleName).put("message", error.message)
                    }
                    entry.put("ms", (System.nanoTime() - requestStarted) / 1_000_000)
                    batches.put(entry)
                    sendStatus(0, Bundle().apply { putString("stream", entry.toString() + "\n") })
                }
                report.put("recommendations", batches).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                targetContext.openFileOutput("p0-native-recommend-report.json", 0).bufferedWriter().use { it.write(report.toString(2)) }
                finish(0, Bundle().apply { putString("report", report.toString()) })
                return
            }
            val sources = if (options.getString("mode") == "search") Prefs(targetContext).sources()
                else targetContext.assets.open("initial-sources.json").bufferedReader().use { api.parseSourceList(it.readText()) }
            val searchStarted = System.nanoTime()
            var firstResultsMs = -1L
            val arrivals = JSONArray()
            val result = api.search(options.getString("query", "三体")!!, sources) { partial ->
                if (partial.items.isNotEmpty()) {
                    val ms = (System.nanoTime() - searchStarted) / 1_000_000
                    if (firstResultsMs < 0) firstResultsMs = ms
                    arrivals.put(JSONObject().put("ms", ms).put("items", partial.items.size).put("respondedSources", partial.reports.size))
                }
            }
            report.put("searchMs", (System.nanoTime() - searchStarted) / 1_000_000)
                .put("firstResultsMs", firstResultsMs).put("arrivals", arrivals)
                .put("items", result.items.size).put("failedSources", result.failures)
            val sourceReports = JSONArray()
            result.reports.forEach { source ->
                sourceReports.put(JSONObject().put("name", source.name).put("host", source.host)
                    .put("ok", source.ok).put("count", source.count).put("pages", source.pages)
                    .put("failedPages", JSONArray(source.failedPages)).put("ms", source.ms)
                    .put("category", source.category).put("error", source.error))
            }
            report.put("sources", sourceReports)
            sendStatus(0, Bundle().apply { putString("stream", "search: ${result.items.size} items; ${result.failures}/${sources.size} sources failed\n") })
            if (options.getString("mode") == "search") {
                report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                targetContext.openFileOutput("performance-search-report.json", 0).bufferedWriter().use { it.write(report.toString(2)) }
                finish(0, Bundle().apply { putString("report", report.toString()) })
                return
            }
            val details = JSONArray()
            for (source in sources) {
                val item = result.items.firstOrNull { it.sourceUrl == source.url } ?: continue
                val detailStarted = System.nanoTime()
                val entry = JSONObject().put("source", source.name).put("vodId", item.vodId)
                try {
                    val detail = api.detail(item.vodId, source)
                    entry.put("detailOk", true).put("title", detail.info.title).put("episodes", detail.episodes.size)
                    val media = detail.episodes.first()
                    entry.put("mediaHost", URI(media).host).put("firstMediaWithoutQuery", media.substringBefore('?'))
                    val connection = URL(media).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "DpadTV-Android")
                    try {
                        entry.put("mediaStatus", connection.responseCode)
                        if (connection.responseCode in 200..299) {
                            entry.put("sampleBytes", connection.inputStream.use { stream ->
                                val buffer = ByteArray(2048)
                                stream.read(buffer)
                            })
                        }
                    } finally { connection.disconnect() }
                } catch (error: Exception) {
                    entry.put(if (entry.optBoolean("detailOk")) "mediaError" else "detailError", error.javaClass.simpleName)
                    if (!entry.has("detailOk")) entry.put("detailOk", false)
                }
                entry.put("ms", (System.nanoTime() - detailStarted) / 1_000_000)
                details.put(entry)
                sendStatus(0, Bundle().apply { putString("stream", "${source.name}: detail=${entry.optBoolean("detailOk")} episodes=${entry.optInt("episodes")} media=${entry.optInt("mediaStatus")}\n") })
            }
            report.put("details", details).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            targetContext.openFileOutput("p0-native-cms-report.json", 0).bufferedWriter().use { it.write(report.toString(2)) }
            finish(0, Bundle().apply { putString("report", report.toString()) })
        } catch (error: Exception) {
            report.put("fatal", error.javaClass.simpleName).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            if (options.getString("mode") in listOf("switch-player", "regression")) report.put("message", error.message)
            targetContext.openFileOutput("p0-native-cms-report.json", 0).bufferedWriter().use { it.write(report.toString(2)) }
            finish(1, Bundle().apply { putString("report", report.toString()) })
        }
    }
}
