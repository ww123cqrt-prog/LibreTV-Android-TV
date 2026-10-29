package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Disposable-emulator regression: restores only the two preference keys it replaces. */
class MainHistoryProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val savedHistory = sp.getString("history", null)
        val savedSources = sp.getString("sources", null)
        val poster = ByteArrayOutputStream().let { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val releaseB = CountDownLatch(1)
        val aRequests = AtomicInteger()
        val bRequests = AtomicInteger()
        lateinit var server: RegressionHttpServer
        server = RegressionHttpServer { uri ->
            when (uri.path) {
                "/poster.png" -> poster
                "/a.mp4", "/b.mp4" -> clip
                else -> {
                    val query = uri.rawQuery.orEmpty().split('&').associate {
                        val parts = it.split('=', limit = 2)
                        parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    }
                    val title = query["wd"].orEmpty()
                    if (title == "History A") aRequests.incrementAndGet()
                    if (title == "History B") { bRequests.incrementAndGet(); releaseB.await() }
                    JSONObject().put("pagecount", 1).put("list", JSONArray().put(JSONObject()
                        .put("vod_id", title).put("vod_name", title).put("vod_year", "2026")
                        .put("vod_pic", server.base + "/poster.png"))).toString().toByteArray()
                }
            }
        }
        val urls = listOf(server.base + "/a.mp4", server.base + "/b.mp4")
        var playback: PlayerActivity? = null
        val rootField = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun findGrid(view: View): GridView? {
            if (view is GridView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findGrid(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun grid(): GridView = findGrid(rootField.get(main) as View) ?: error("History grid missing")
        fun await(message: String, condition: () -> Boolean) {
            val end = System.nanoTime() + 8_000_000_000L
            while (System.nanoTime() < end) {
                var done = false
                onMain { done = condition() }
                if (done) return
                Thread.sleep(30)
            }
            error(message)
        }
        try {
            val cover = if (caseId != "r7") server.base + "/poster.png" else ""
            val records = JSONArray()
            urls.forEachIndexed { index, url -> records.put(JSONObject()
                .put("title", if (index == 0) "History A" else "History B")
                .put("playUrl", url).put("positionMs", 1_000).put("durationMs", 12_000)
                .put("updatedAt", 1).put("cover", cover)) }
            sp.edit().putString("history", records.toString()).commit()
            prefs.saveSources(if (caseId == "r7") listOf(SourceConfig("fixture", server.base + "/cms")) else emptyList())
            onMain {
                // Enter the fixture after cancelling the startup recommendation dialog/request.
                (MainActivity::class.java.getDeclaredField("job").apply { isAccessible = true }.get(main) as? Job)?.cancel()
                MainActivity::class.java.getDeclaredMethod("dismissProgress").apply { isAccessible = true }.invoke(main)
                MainActivity::class.java.getDeclaredMethod("showHistoryCards").apply { isAccessible = true }.invoke(main)
                grid().requestFocus()
                grid().setSelection(1)
            }
            instrumentation.waitForIdleSync()
            if (caseId != "r7") {
                val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
                onMain {
                    MainActivity::class.java.getDeclaredMethod("play", String::class.java, String::class.java)
                        .apply { isAccessible = true }.invoke(main, urls[1], "History B")
                }
                playback = monitor.waitForActivityWithTimeout(6_000) as? PlayerActivity ?: error("Player did not open")
                instrumentation.removeMonitor(monitor)
                val target = playback
                val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                lateinit var player: ExoPlayer
                onMain { player = field.get(target) as ExoPlayer }
                await("Player not ready") { player.playbackState == Player.STATE_READY }
                var bufferingAtExit = false
                var positionSavedOnPause = 0L
                onMain {
                    player.pause()
                    player.seekTo(0, 6_000)
                    if (caseId == "r1-seek") {
                        bufferingAtExit = player.playbackState == Player.STATE_BUFFERING && player.duration > 0
                        instrumentation.callActivityOnPause(target)
                        positionSavedOnPause = prefs.history().first { it.playUrl == urls[1] }.positionMs
                    }
                }
                if (caseId == "r1-seek") {
                    check(bufferingAtExit) { "Immediate-exit fixture did not exercise a buffered seek" }
                    check(positionSavedOnPause >= 5_900) { "Immediate exit while seeking did not save the requested position" }
                }
                if (caseId == "r1") await("Seek did not settle") {
                    player.playbackState == Player.STATE_READY && player.currentPosition >= 5_900
                }
                onMain { target.finish() }
                instrumentation.waitForIdleSync()
                await("History did not receive the last saved position") {
                    (grid().adapter.getItem(0) as HistoryEntry).playUrl == urls[1] &&
                        (grid().adapter.getItem(0) as HistoryEntry).positionMs >= 5_900
                }
                onMain {
                    check(grid().hasFocus()) { "History grid lost focus" }
                    check((grid().selectedItem as HistoryEntry).playUrl == urls[1]) { "Selected history changed" }
                }
                return JSONObject().put("case", caseId).put("latestProgressVisible", true).put("selectedHistoryPreserved", true)
            }
            await("First history cover did not finish") { prefs.history().first { it.playUrl == urls[0] }.cover.isNotEmpty() }
            // Give an incorrect recursive refresh time to launch a duplicate request for B.
            Thread.sleep(250)
            releaseB.countDown()
            await("Second history cover did not finish") { prefs.history().all { it.cover.isNotEmpty() } }
            instrumentation.waitForIdleSync()
            check(aRequests.get() == 1 && bRequests.get() == 1) {
                "History cover searches repeated: A=${aRequests.get()}, B=${bRequests.get()}"
            }
            onMain {
                check(grid().hasFocus()) { "Cover completion rebuilt the page and lost focus" }
                check((grid().selectedItem as HistoryEntry).playUrl == urls[1])
            }
            return JSONObject().put("case", caseId).put("aRequests", aRequests.get())
                .put("bRequests", bRequests.get()).put("focusPreserved", true)
        } finally {
            releaseB.countDown()
            onMain { playback?.let { if (!it.isFinishing) it.finish() }; main.finish() }
            instrumentation.waitForIdleSync()
            sp.edit().putString("history", savedHistory).putString("sources", savedSources).commit()
            if (caseId != "r7") urls.forEach { url ->
                prefs.clearProgress(url)
                PlaybackCache.get(context).removeResource(url)
            }
            server.close()
        }
    }
}
