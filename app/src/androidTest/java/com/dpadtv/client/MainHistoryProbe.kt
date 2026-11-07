package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Disposable-emulator regression; restores the preferences it replaces. */
class MainHistoryProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        val continuation = caseId == "history-next"
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val savedHistory = sp.getString("history", null)
        val savedSources = sp.getString("sources", null)
        val savedResume = if (sp.contains("resume_enabled")) prefs.resumeEnabled else null
        val savedThreshold = if (sp.contains("resume_threshold")) prefs.resumeThresholdSeconds else null
        val poster = ByteArrayOutputStream().let { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val releaseB = CountDownLatch(1)
        val releaseEpisodes = CountDownLatch(1)
        val aRequests = AtomicInteger()
        val bRequests = AtomicInteger()
        lateinit var server: RegressionHttpServer
        server = RegressionHttpServer { uri ->
            when (uri.path) {
                "/poster.png" -> poster
                "/a.mp4", "/b.mp4", "/a-first.mp4", "/a-next.mp4" -> clip
                else -> {
                    val query = uri.rawQuery.orEmpty().split('&').associate {
                        val parts = it.split('=', limit = 2)
                        parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    }
                    val title = query["wd"].orEmpty()
                    if (title == "History A") aRequests.incrementAndGet()
                    if (title == "History B") {
                        bRequests.incrementAndGet()
                        if (caseId == "r7") releaseB.await()
                    }
                    val id = query["ids"]
                    if (id == "series-a") releaseEpisodes.await(10, TimeUnit.SECONDS)
                    val row = JSONObject().put("vod_id", if (title == "History B") "series-b" else title)
                        .put("vod_name", when (id) {
                            "series-a" -> "History A"
                            "series-b" -> "History B"
                            else -> title
                        }).put("vod_year", "2026").put("vod_pic", server.base + "/poster.png")
                    if (id == "series-a") row.put("vod_play_url", listOf("/a-first.mp4", "/a.mp4", "/a-next.mp4")
                        .mapIndexed { index, path -> "第${index + 1}集\$${server.base}$path" }.joinToString("#"))
                    if (id == "series-b") row.put("vod_play_url", "第1集\$${server.base}/unmatched.mp4")
                    JSONObject().put("pagecount", 1).put("list", JSONArray().put(row)).toString().toByteArray()
                }
            }
        }
        val urls = listOf(server.base + "/a.mp4", server.base + "/b.mp4")
        val nextUrl = server.base + "/a-next.mp4"
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
        fun openHistory(url: String): Pair<PlayerActivity, ExoPlayer> {
            val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
            val target = try {
                onMain {
                    val grid = grid()
                    val index = (0 until grid.count).first { (grid.getItemAtPosition(it) as HistoryEntry).playUrl == url }
                    check(grid.performItemClick(null, index, index.toLong())) { "History click was not handled" }
                }
                monitor.waitForActivityWithTimeout(6_000) as? PlayerActivity ?: error("Player did not open")
            } finally { instrumentation.removeMonitor(monitor) }
            playback = target
            val field = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            lateinit var player: ExoPlayer
            onMain { player = field.get(target) as ExoPlayer }
            return target to player
        }
        try {
            val cover = if (caseId != "r7") server.base + "/poster.png" else ""
            val records = JSONArray()
            urls.forEachIndexed { index, url ->
                val title = if (index == 0) "History A" else "History B"
                val row = JSONObject().put("title", if (continuation) "$title · 第 2/3 集" else title)
                    .put("playUrl", url).put("positionMs", if (continuation) 6_000 else 1_000).put("durationMs", 12_000)
                    .put("updatedAt", 1).put("cover", cover)
                if (continuation && index == 0) row.put("source", JSONObject().put("url", server.base + "/cms")
                    .put("vodId", "series-a").put("title", title).put("year", "2026"))
                records.put(row)
            }
            sp.edit().putString("history", records.toString()).commit()
            prefs.saveSources(if (caseId == "r7" || continuation) listOf(SourceConfig("fixture", server.base + "/cms")) else emptyList())
            onMain {
                // Enter the fixture after cancelling the startup recommendation dialog/request.
                (MainActivity::class.java.getDeclaredField("job").apply { isAccessible = true }.get(main) as? Job)?.cancel()
                MainActivity::class.java.getDeclaredMethod("dismissProgress").apply { isAccessible = true }.invoke(main)
                MainActivity::class.java.getDeclaredMethod("showHistoryCards").apply { isAccessible = true }.invoke(main)
                grid().requestFocus()
                grid().setSelection(1)
            }
            instrumentation.waitForIdleSync()
            if (continuation) {
                prefs.resumeEnabled = true
                prefs.resumeThresholdSeconds = 5
                prefs.saveProgress(urls[0], 6_000L)
                val (target, player) = openHistory(urls[0])
                await("The saved episode did not resume while CMS detail was held") {
                    player.playbackState == Player.STATE_READY && player.currentPosition >= 5_900 && player.mediaItemCount == 1
                }
                var resumePosition = 0L
                onMain { player.pause(); resumePosition = player.currentPosition }
                releaseEpisodes.countDown()
                await("Same-series episode list was not restored") { player.mediaItemCount == 3 && player.currentMediaItemIndex == 1 }
                onMain {
                    check(player.currentMediaItem?.localConfiguration?.uri.toString() == urls[0])
                    check(kotlin.math.abs(player.currentPosition - resumePosition) < 100) { "Restoring the list reset playback" }
                    target.onKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                }
                await("Next did not open the following episode of History A") {
                    player.currentMediaItem?.localConfiguration?.uri.toString() == nextUrl && player.playbackState == Player.STATE_READY
                }
                check(server.count("/b.mp4") == 0 && aRequests.get() == 0) { "Resumed the neighboring history row or searched despite a saved source ID" }
                onMain { player.seekTo(2, 6_000L) }
                await("Next episode seek did not settle") { player.playbackState == Player.STATE_READY && player.currentPosition >= 5_900 }
                onMain { target.finish() }
                await("Next episode lost the series source") {
                    prefs.history().firstOrNull { it.playUrl == nextUrl }?.source?.vodId == "series-a"
                }
                instrumentation.waitForIdleSync()

                val (legacy, fallback) = openHistory(urls[1])
                val job = PlayerActivity::class.java.getDeclaredField("historyPlaylistJob").apply { isAccessible = true }
                await("Legacy history fallback did not settle") {
                    (job.get(legacy) as? Job)?.isCompleted == true && fallback.playbackState == Player.STATE_READY
                }
                onMain {
                    check(fallback.mediaItemCount == 1 && !fallback.hasNextMediaItem())
                    legacy.onKeyDown(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    check(fallback.currentMediaItem?.localConfiguration?.uri.toString() == urls[1])
                }
                check(bRequests.get() == 1) { "Legacy history did not check the exact title" }
                return JSONObject().put("case", caseId).put("fastResume", true)
                    .put("sameSeriesNext", true).put("legacyFallback", true)
            }
            if (caseId != "r7") {
                val (target, player) = openHistory(urls[1])
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
            releaseEpisodes.countDown()
            onMain { playback?.let { if (!it.isFinishing) it.finish() }; main.finish() }
            instrumentation.waitForIdleSync()
            sp.edit().putString("history", savedHistory).putString("sources", savedSources).apply {
                if (continuation) {
                    if (savedResume == null) remove("resume_enabled") else putBoolean("resume_enabled", savedResume)
                    if (savedThreshold == null) remove("resume_threshold") else putInt("resume_threshold", savedThreshold)
                }
            }.commit()
            if (caseId != "r7") (urls + nextUrl).forEach { url ->
                prefs.clearProgress(url)
                PlaybackCache.get(context).removeResource(url)
            }
            server.close()
        }
    }
}
