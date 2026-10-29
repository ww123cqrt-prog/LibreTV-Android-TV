package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject

/** Local clips exercise real player state without changing source settings. */
class PlaybackLifecycleProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        require(caseId == "r2" || caseId == "r3")
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val server = RegressionHttpServer { clip }
        val urls = listOf("${server.base}/lifecycle-a.mp4", "${server.base}/lifecycle-b.mp4")
        var activity: PlayerActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
                putExtra(PlayerActivity.EXTRA_INDEX, 0)
                putExtra(PlayerActivity.EXTRA_TITLE, "播放生命周期验收片")
            }) as PlayerActivity
            val target = activity
            val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val seekBy = PlayerActivity::class.java.getDeclaredMethod("seekBy", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            lateinit var player: ExoPlayer
            fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
            onMain { player = playerField.get(target) as ExoPlayer }
            fun await(message: String, condition: () -> Boolean) {
                val end = System.nanoTime() + 25_000_000_000L
                while (System.nanoTime() < end) {
                    var done = false
                    onMain { done = condition() }
                    if (done) return
                    Thread.sleep(50)
                }
                error(message)
            }
            fun state(): JSONObject {
                val result = JSONObject()
                onMain {
                    result.put("episode", player.currentMediaItemIndex + 1)
                        .put("positionMs", player.currentPosition)
                        .put("playWhenReady", player.playWhenReady)
                        .put("state", player.playbackState)
                }
                return result
            }
            await("Lifecycle fixture did not become ready") { player.playbackState == Player.STATE_READY }
            if (caseId == "r2") {
                onMain { player.pause(); player.seekTo(0, 1_000) }
                await("First clip seek did not settle") {
                    player.playbackState == Player.STATE_READY && player.currentPosition in 900L..1_100L
                }
                onMain {
                    seekBy.invoke(target, 5_000L)
                    player.seekTo(1, 0)
                    seekBy.invoke(target, 1_000L)
                }
                await("Second clip did not become ready") {
                    player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_READY
                }
                val after = state()
                check(after.getLong("positionMs") in 900L..1_200L) {
                    "Pending seek leaked into the next episode: $after"
                }
                return JSONObject().put("case", caseId).put("afterEpisodeSeek", after)
            }

            onMain { player.pause(); player.seekTo(0, 6_000) }
            await("Background fixture seek did not settle") {
                player.playbackState == Player.STATE_READY && player.currentPosition in 5_900L..6_100L
            }
            onMain {
                instrumentation.callActivityOnPause(target)
                instrumentation.callActivityOnStop(target)
            }
            val stoppedAt = prefs.history().first { it.playUrl == urls[0] }.updatedAt
            Thread.sleep(11_000)
            val afterWait = prefs.history().first { it.playUrl == urls[0] }.updatedAt
            check(afterWait == stoppedAt) {
                "Stopped player kept rewriting history: stoppedAt=$stoppedAt afterWait=$afterWait ${state()}"
            }
            onMain {
                instrumentation.callActivityOnStart(target)
                instrumentation.callActivityOnResume(target)
            }
            await("Foreground player did not resume periodic history saves") {
                prefs.history().first { it.playUrl == urls[0] }.updatedAt > afterWait
            }
            val resumedAt = prefs.history().first { it.playUrl == urls[0] }.updatedAt
            return JSONObject().put("case", caseId).put("stoppedUpdatedAt", stoppedAt)
                .put("afterWaitUpdatedAt", afterWait).put("resumedUpdatedAt", resumedAt)
                .put("waitMs", 11_000).put("player", state())
        } finally {
            activity?.let { target -> instrumentation.runOnMainSync { if (!target.isFinishing) target.finish() } }
            instrumentation.waitForIdleSync()
            urls.forEach { url ->
                if (prefs.history().any { it.playUrl == url }) prefs.removeHistory(url)
                if (prefs.progressOf(url) != 0L) prefs.clearProgress(url)
                PlaybackCache.get(context).removeResource(url)
            }
            server.close()
        }
    }
}
