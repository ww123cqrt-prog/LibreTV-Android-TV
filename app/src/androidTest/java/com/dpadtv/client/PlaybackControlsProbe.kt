package com.dpadtv.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Episode selection is N5-safe. Recovery cases replace sources on the disposable emulator only. */
class PlaybackControlsProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        val before = sp.all.toMap()
        val savedSources = sp.getString("sources", null)
        val recovering = caseId != "episode-picker"
        if (recovering) check(android.os.Build.MODEL == "AOSP TV on x86") { "Recovery fixture is emulator-only" }
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val title = "Recovery Fixture"
        val failOriginal = AtomicBoolean(true)
        val releaseRefresh = CountDownLatch(1)
        lateinit var server: RegressionHttpServer
        server = RegressionHttpServer(statusCode = { uri ->
            if ((uri.path.startsWith("/failed/") && failOriginal.get()) ||
                (caseId == "recovery-fallback" && uri.path.startsWith("/alternate/"))) 503 else 200
        }) { uri ->
            if (caseId == "recovery-refresh" && uri.path.startsWith("/failed/") && !failOriginal.get()) {
                releaseRefresh.await()
            }
            if (uri.path == "/cms") {
                val playList = (1..3).joinToString("#") { "第${it}集${'$'}${server.base}/alternate/ep$it.mp4" }
                JSONObject().put("pagecount", 1).put("list", JSONArray().put(
                    JSONObject().put("vod_id", "alternate").put("vod_name", title)
                        .put("vod_year", "2026").put("vod_play_url", playList)
                )).toString().toByteArray()
            } else clip
        }
        val urls = (1..3).map { "${server.base}/${if (recovering && it == 2) "failed" else "episode"}/ep$it.mp4" }
        val alternateUrls = (1..3).map { "${server.base}/alternate/ep$it.mp4" }
        var activity: PlayerActivity? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun await(message: String, seconds: Long = 12, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                var done = false
                onMain { done = condition() }
                if (done) return
                Thread.sleep(25)
            }
            error(message)
        }
        lateinit var player: ExoPlayer
        try {
            instrumentation.setInTouchMode(false)
            if (recovering) prefs.saveSources(listOf(SourceConfig("Fixture alternate", server.base + "/cms")))
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
                putExtra(PlayerActivity.EXTRA_INDEX, 1)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SEARCH_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE_NAME, "Fixture original")
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, server.base + "/original-cms")
                putExtra(PlayerActivity.EXTRA_VOD_ID, "original")
                putExtra(PlayerActivity.EXTRA_YEAR, "2026")
            }) as PlayerActivity
            onMain {
                player = field("player").get(activity) as ExoPlayer
                player.pause()
                player.seekTo(1, if (recovering) 1_500 else 0)
            }
            if (!recovering) {
                await("Episode fixture did not decode") { player.playbackState == Player.STATE_READY }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                await("Player action bar did not take focus") {
                    (activity!!.currentFocus as? TextView)?.text.toString() == "换源"
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                await("Episode action did not take focus") {
                    (activity!!.currentFocus as? TextView)?.text.toString() == "选集"
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Episode picker did not select current episode") {
                    val dialog = field("episodeDialog").get(activity) as? AlertDialog
                    dialog?.isShowing == true && dialog.window?.decorView?.hasWindowFocus() == true &&
                        dialog.listView.checkedItemPosition == 1
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                await("Selecting episode 3 did not use the existing player") {
                    player.currentMediaItemIndex == 2 && player.playbackState == Player.STATE_READY &&
                        !player.playWhenReady && field("player").get(activity) === player
                }
                onMain {
                    check((field("urls").get(activity) as List<*>)[2] == urls[2])
                    check((field("episodeAction").get(activity) as View).visibility == View.VISIBLE)
                }
                return JSONObject().put("case", caseId).put("selectedEpisode", 3)
                    .put("samePlayer", true).put("pausedStatePreserved", true)
            }
            await("Retry exhaustion did not offer a same-series source", 18) {
                val dialog = field("sourceDialog").get(activity) as? AlertDialog
                player.playerError != null && dialog?.isShowing == true &&
                    dialog.window?.decorView?.hasWindowFocus() == true && dialog.listView?.adapter?.count == 1
            }
            val retries = server.count("/failed/ep2.mp4")
            check(retries in 2..6) { "Unexpected failed-load request count $retries" }
            if (caseId in listOf("recovery-cancel", "episode-after-error", "recovery-refresh")) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Cancel did not close source recovery") {
                    (field("sourceDialog").get(activity) as? AlertDialog)?.isShowing != true &&
                        activity!!.window.decorView.hasWindowFocus()
                }
                val requests = server.count("/cms")
                when (caseId) {
                    "episode-after-error" -> {
                        onMain {
                            PlayerActivity::class.java.getDeclaredMethod("showEpisodePicker")
                                .apply { isAccessible = true }.invoke(activity)
                        }
                        await("Episode picker did not open after error") {
                            (field("episodeDialog").get(activity) as? AlertDialog)?.let {
                                it.isShowing && it.window?.decorView?.hasWindowFocus() == true
                            } == true
                        }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        await("Episode selected after an error did not prepare") {
                            player.playbackState == Player.STATE_READY && player.currentMediaItemIndex == 2
                        }
                    }
                    "recovery-refresh" -> {
                        failOriginal.set(false)
                        onMain {
                            val bar = field("actionBar").get(activity) as android.view.ViewGroup
                            (0 until bar.childCount).map { bar.getChildAt(it) as TextView }
                                .first { it.text.toString() == "刷新" }.performClick()
                        }
                        await("Refresh did not begin loading") {
                            player.playbackState == Player.STATE_BUFFERING && player.playerError == null
                        }
                        onMain {
                            PlayerActivity::class.java.getDeclaredMethod("showSpeedMenu")
                                .apply { isAccessible = true }.invoke(activity)
                        }
                        await("Speed dialog did not receive the window focus") { !activity!!.window.decorView.hasWindowFocus() }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        await("Closing speed dialog did not return the focus") { activity!!.window.decorView.hasWindowFocus() }
                        Thread.sleep(250)
                        onMain { check((field("sourceDialog").get(activity) as? AlertDialog)?.isShowing != true) }
                        releaseRefresh.countDown()
                        await("Same-item refresh retained the old error") {
                            player.playbackState == Player.STATE_READY && !field("hasError").getBoolean(activity)
                        }
                    }
                }
                onMain {
                    instrumentation.callActivityOnPause(activity)
                    instrumentation.callActivityOnResume(activity)
                }
                Thread.sleep(300)
                var selectedEpisode = 0
                onMain {
                    check((field("sourceDialog").get(activity) as? AlertDialog)?.isShowing != true)
                    selectedEpisode = player.currentMediaItemIndex + 1
                }
                check(server.count("/cms") == requests)
                return JSONObject().put("case", caseId).put("retries", retries)
                    .put("cancelDoesNotReopen", true).put("selectedEpisode", selectedEpisode)
            }
            // Select the candidate through the actual dialog callback.
            onMain {
                val dialog = field("sourceDialog").get(activity) as AlertDialog
                dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
            }
            if (caseId == "recovery-fallback") {
                await("Failed candidate did not settle back on the original URL", 18) {
                    field("sourceBackup").get(activity) == null && player.playerError != null &&
                        player.currentMediaItem?.localConfiguration?.uri.toString() == urls[1]
                }
                Thread.sleep(300)
                onMain { check((field("sourceDialog").get(activity) as? AlertDialog)?.isShowing != true) }
                return JSONObject().put("case", caseId).put("failedCandidateDoesNotLoop", true)
            }
            await("Alternative source did not retain episode 2 and the seek position") {
                player.playbackState == Player.STATE_READY && field("sourceBackup").get(activity) == null &&
                    player.currentMediaItemIndex == 1 && player.currentPosition >= 1_400 &&
                    !player.playWhenReady && player.currentMediaItem?.localConfiguration?.uri.toString() == alternateUrls[1]
            }
            return JSONObject().put("case", caseId).put("retries", retries)
                .put("sameEpisode", true).put("positionPreserved", true).put("pausedStatePreserved", true)
        } finally {
            releaseRefresh.countDown()
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            if (recovering) sp.edit().apply {
                if (savedSources == null) remove("sources") else putString("sources", savedSources)
            }.commit()
            (urls + alternateUrls).forEach { PlaybackCache.get(context).removeResource(it) }
            server.close()
            check(before == sp.all) { "Playback controls probe changed app preferences" }
        }
    }
}
