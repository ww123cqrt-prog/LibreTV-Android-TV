package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException

/** A test-APK-only local clip; never contacts an external source or edits source settings. */
class PlaybackSwitchProbe(private val instrumentation: Instrumentation) {
    fun run(): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val urls = listOf("$base/a.mp4", "$base/b.mp4", "$base/bad.mp4")
        val worker = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (closed: SocketException) {
                    if (server.isClosed) break else throw closed
                }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = if (request.contains("/bad.mp4")) "invalid mp4".toByteArray() else clip
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        var activity: PlayerActivity? = null
        try {
            val title = "换源验收片"
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, arrayListOf(urls[0], urls[0]))
                putExtra(PlayerActivity.EXTRA_INDEX, 1)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SEARCH_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE_NAME, "Test A")
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, "https://fixture.invalid/a")
                putExtra(PlayerActivity.EXTRA_VOD_ID, "a")
                putExtra(PlayerActivity.EXTRA_YEAR, "2026")
            }) as PlayerActivity
            val target = activity
            val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val sourceField = PlayerActivity::class.java.getDeclaredField("currentSource").apply { isAccessible = true }
            val backupField = PlayerActivity::class.java.getDeclaredField("sourceBackup").apply { isAccessible = true }
            val switch = PlayerActivity::class.java.getDeclaredMethod("switchToSource", SourceCandidate::class.java).apply { isAccessible = true }
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync { player = playerField.get(target) as ExoPlayer }
            fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
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
            fun candidate(name: String, url: String, count: Int) = SourceCandidate(
                SearchItem(name, name, title, "", "电影", "2026", "", "https://fixture.invalid/$name"),
                Detail(List(count) { url }, VideoInfo(title, "", "", "", "2026", "", "")), 1,
            )
            fun ready(name: String, url: String): Boolean =
                player.playbackState == Player.STATE_READY && backupField.get(target) == null &&
                    (sourceField.get(target) as SearchItem).sourceName == name &&
                    player.currentMediaItem?.localConfiguration?.uri.toString() == url
            fun state(): JSONObject {
                val result = JSONObject()
                onMain {
                    result.put("source", (sourceField.get(target) as SearchItem).sourceName)
                        .put("episode", player.currentMediaItemIndex + 1).put("positionMs", player.currentPosition)
                        .put("playWhenReady", player.playWhenReady).put("state", player.playbackState)
                }
                return result
            }

            await("Initial fixture did not become ready") { player.playbackState == Player.STATE_READY }
            onMain { player.pause(); player.seekTo(1, 1_000) }
            await("Fixture seek did not settle") { ready("Test A", urls[0]) && player.currentPosition >= 900 }
            onMain { switch.invoke(target, candidate("Test B", urls[1], 2)) }
            await("Paused switch did not render") { ready("Test B", urls[1]) }
            val paused = state()
            check(paused.getInt("episode") == 2 && !paused.getBoolean("playWhenReady"))
            check(paused.getLong("positionMs") in 900..1_500)

            onMain { switch.invoke(target, candidate("Test bad", urls[2], 2)) }
            await("Bad source did not roll back") { ready("Test B", urls[1]) }
            val rollback = state()
            check(rollback.getInt("episode") == 2 && !rollback.getBoolean("playWhenReady"))
            check(rollback.getLong("positionMs") in 900..1_500)

            onMain { player.play(); switch.invoke(target, candidate("Test A", urls[0], 2)) }
            await("Playing switch did not render") { ready("Test A", urls[0]) }
            val playing = state()
            check(playing.getInt("episode") == 2 && playing.getBoolean("playWhenReady"))
            check(playing.getLong("positionMs") in 900..3_000)
            onMain { player.pause(); switch.invoke(target, candidate("Missing", urls[1], 1)) }
            val missing = state()
            check(missing.getString("source") == "Test A" && missing.getInt("episode") == 2)
            check(!missing.getBoolean("playWhenReady"))
            onMain { check(backupField.get(target) == null) }
            onMain { target.finish() }
            instrumentation.waitForIdleSync()
            check(before == preferences.all) { "Fixture changed user preferences" }
            return JSONObject().put("pausedSwitch", paused).put("badSourceRollback", rollback)
                .put("playingSwitch", playing).put("missingEpisodeUnchanged", missing)
                .put("preferencesUnchanged", true).put("fixture", "generated testsrc2; test APK only")
        } finally {
            activity?.let { target -> instrumentation.runOnMainSync { if (!target.isFinishing) target.finish() } }
            instrumentation.waitForIdleSync()
            val prefs = Prefs(context)
            // Only remove this probe's temporary resources, including after a failed assertion.
            urls.forEach { url ->
                if (prefs.history().any { it.playUrl == url }) prefs.removeHistory(url)
                if (prefs.progressOf(url) != 0L) prefs.clearProgress(url)
                PlaybackCache.get(context).removeResource(url)
            }
            server.close()
            worker.join(1_000)
        }
    }
}
