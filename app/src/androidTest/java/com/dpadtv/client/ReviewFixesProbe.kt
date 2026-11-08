package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import org.json.JSONObject

/** Review regressions use disposable emulator data and generated local media only. */
class ReviewFixesProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        check(android.os.Build.MODEL == "AOSP TV on x86") { "Review fixture is emulator-only" }
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val saved = AppBackup.decode(prefs.exportBackup())
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val server = RegressionHttpServer { uri -> if (uri.path == "/bad.mp4") byteArrayOf(0) else clip }
        var activity: android.app.Activity? = null
        fun onMain(action: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun invoke(target: Any, name: String) = target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target)
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 12_000_000_000L
            while (System.nanoTime() < deadline) {
                var done = false
                onMain { done = condition() }
                if (done) return
                Thread.sleep(25)
            }
            error(message)
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun startPlayer(): PlayerActivity {
            val target = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, arrayListOf(server.base + "/one.mp4"))
                putExtra(PlayerActivity.EXTRA_TITLE, "审查测试剧")
                putExtra(PlayerActivity.EXTRA_SEARCH_TITLE, "审查测试剧")
                putExtra(PlayerActivity.EXTRA_SOURCE_NAME, "A")
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, "https://fixture.invalid/a")
                putExtra(PlayerActivity.EXTRA_VOD_ID, "a")
            }) as PlayerActivity
            activity = target
            await("Initial local video did not render") {
                (field(target, "player") as ExoPlayer).playbackState == Player.STATE_READY &&
                    field(target, "videoFrameRendered") == true
            }
            return target
        }
        try {
            prefs.autoNext = false
            prefs.nextEpisodePreloadEnabled = false
            when (caseId) {
                "review-replay" -> {
                    prefs.resumeEnabled = true
                    prefs.saveProgress(server.base + "/one.mp4", 8_000)
                    val target = startPlayer()
                    val player = field(target, "player") as ExoPlayer
                    onMain {
                        player.pause()
                        invoke(target, "showActions")
                        descendants(target.window.decorView).filterIsInstance<TextView>()
                            .single { it.text.toString() == "重播" }.performClick()
                    }
                    await("Replay did not seek to the start") { player.currentPosition < 5_000 }
                    onMain { target.finish() }
                    instrumentation.waitForIdleSync()
                    check(prefs.progressOf(server.base + "/one.mp4") == 0L) { "Replay retained the old resume position" }
                    val reopened = startPlayer()
                    onMain {
                        check((field(reopened, "player") as ExoPlayer).currentPosition < 5_000) { "Reopen resumed the old position" }
                    }
                }
                "review-favorite-switch" -> {
                    prefs.removeFavorite(FavoriteEntry("审查测试剧", "", "", 0))
                    prefs.removeFavorite(FavoriteEntry("审查测试剧", "", "2026", 0))
                    val target = startPlayer()
                    onMain { (field(target, "player") as ExoPlayer).pause() }
                    onMain { (field(target, "favoriteAction") as TextView).performClick() }
                    check(prefs.isFavorite("审查测试剧", ""))
                    val switch = PlayerActivity::class.java.getDeclaredMethod("switchToSource", SourceCandidate::class.java)
                        .apply { isAccessible = true }
                    fun candidate(name: String, year: String, path: String) = SourceCandidate(
                        SearchItem(name, name, "审查测试剧", "", "剧集", year, "", "https://fixture.invalid/$name"),
                        Detail(listOf(server.base + path), VideoInfo("审查测试剧", "", "", "", year, "", "")), 1)
                    onMain { switch.invoke(target, candidate("B", "2026", "/two.mp4")) }
                    await("Switch did not render") { field(target, "sourceBackup") == null &&
                        (field(target, "currentSource") as SearchItem).sourceName == "B" }
                    onMain {
                        check(field(target, "favoriteYear") == "2026") { "Successful switch retained the unknown favorite year" }
                        check((field(target, "favoriteAction") as TextView).text == "已收藏")
                    }
                    check(prefs.favorites().count { it.title == "审查测试剧" } == 1)
                    check(prefs.isFavorite("审查测试剧", "2026") && !prefs.isFavorite("审查测试剧", ""))
                    onMain { switch.invoke(target, candidate("bad", "2027", "/bad.mp4")) }
                    await("Failed switch did not roll back") { field(target, "sourceBackup") == null &&
                        (field(target, "currentSource") as SearchItem).sourceName == "B" }
                    check(prefs.isFavorite("审查测试剧", "2026") && !prefs.isFavorite("审查测试剧", "2027"))
                }
                "review-clear-cache", "review-sources-back" -> {
                    val target = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                    activity = target
                    onMain {
                        (field(target, "recommendJob") as? Job)?.cancel()
                        invoke(target, "showSetup")
                    }
                    if (caseId == "review-clear-cache") {
                        val cache = PlaybackCache.get(context)
                        val key = "review-cache-${System.nanoTime()}"
                        val hole = cache.startReadWrite(key, 0, 1024)
                        val file = cache.startFile(key, 0, 1024)
                        file.writeBytes(ByteArray(1024) { 1 })
                        cache.commitFile(file, 1024)
                        cache.releaseHoleSpan(hole)
                        check(cache.cacheSpace > 0) { "Fixture did not populate the disk cache" }
                        onMain { invoke(target, "showClearCache") }
                        instrumentation.waitForIdleSync()
                        await("Clear-cache dialog did not acquire focus") {
                            WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() &&
                                it.findViewById<View>(android.R.id.button1) != null }
                        }
                        onMain {
                            val window = WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                            window.findViewById<View>(android.R.id.button1).performClick()
                        }
                        await("Playback cache was not cleared") { PlaybackCache.get(context).cacheSpace == 0L }
                        check(prefs.sources() == saved.sources) { "Clearing cache changed source settings" }
                    } else {
                        val show = MainActivity::class.java.getDeclaredMethod("showSources", Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                        onMain {
                            show.invoke(target, false) // Enter from settings.
                            show.invoke(target, false) // Refresh after a source change.
                            target.onBackPressed()
                            check(field(target, "currentScreen") == "setup") { "Source refresh lost the settings return path" }
                            show.invoke(target, false)
                            invoke(target, "showAddSource")
                            target.onBackPressed()
                            target.onBackPressed()
                            check(field(target, "currentScreen") == "setup") { "Source child return lost the settings origin" }
                            show.invoke(target, true) // Explicit top-level tab entry.
                            target.onBackPressed()
                            check(field(target, "currentScreen") == "sources") { "Top-level source tab was treated as settings" }
                        }
                    }
                }
                else -> error("Unknown review fixture $caseId")
            }
            return JSONObject().put("case", caseId).put("passed", true)
        } finally {
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            prefs.restoreBackup(saved)
            server.close()
        }
    }
}
