package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled local media; never changes sources, settings or existing history. */
class PlayerToolsProbe(private val instrumentation: Instrumentation) {
    /** Observes the real playback callbacks scheduling the immediate successor; does not attribute latency. */
    fun prefetchAuto(): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        check(Prefs(context).nextEpisodePreloadEnabled) { "Enable next-episode preloading on the disposable test device first" }
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val server = RegressionHttpServer { clip }
        val urls = listOf("${server.base}/auto-current.mp4", "${server.base}/auto-next.mp4")
        var activity: PlayerActivity? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
                putExtra(PlayerActivity.EXTRA_TITLE, "预取自动调度验收")
            }) as PlayerActivity
            val target = activity
            var observed = false
            var bufferedMs = 0L
            var fullyBuffered = false
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < end) {
                onMain {
                    val player = field("player").get(target) as ExoPlayer
                    observed = field("prefetchUrl").get(target) == urls[1] || field("completedPrefetchUrl").get(target) == urls[1]
                    if (observed) {
                        bufferedMs = player.bufferedPosition - player.currentPosition
                        fullyBuffered = player.duration > 0 && player.bufferedPosition >= player.duration
                    }
                    if (observed || player.currentPosition >= 3_000L) player.pause()
                }
                if (observed) break
                Thread.sleep(25)
            }
            check(observed) { "Real playback callbacks never scheduled the next-episode worker" }
            check(bufferedMs >= 15_000L || fullyBuffered) { "Warm-up started without enough current-episode buffer" }
            return JSONObject().put("case", "player-prefetch-auto").put("scheduledByPlaybackCallbacks", true)
                .put("currentBufferedMs", bufferedMs).put("currentFullyBuffered", fullyBuffered)
        } finally {
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            urls.forEach { PlaybackCache.get(context).removeResource(it) }
            server.close()
            check(before == preferences.all) { "Automatic prefetch fixture changed preferences" }
        }
    }

    /** Tests the Activity's real lifecycle cancellation, independently of Media3's own playlist preparation. */
    fun prefetchPause(): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val releaseSecond = CountDownLatch(1)
        val requestedSecond = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val completedNormally = AtomicBoolean(false)
        val manifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            warm-first.ts
            #EXTINF:4,
            warm-second.ts
            #EXTINF:4,
            warm-third.ts
            #EXT-X-ENDLIST
        """.trimIndent().toByteArray()
        val server = RegressionHttpServer { uri ->
            when (uri.path) {
                "/current.mp4" -> clip
                "/warm.m3u8" -> manifest
                "/warm-second.ts" -> { requestedSecond.countDown(); releaseSecond.await(); ByteArray(4096) }
                else -> ByteArray(4096)
            }
        }
        var activity: PlayerActivity? = null
        var task: Job? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun field(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, arrayListOf(server.base + "/current.mp4"))
                putExtra(PlayerActivity.EXTRA_TITLE, "预取生命周期验收")
            }) as PlayerActivity
            val target = activity
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var ready = false
            while (System.nanoTime() < end) {
                onMain {
                    val player = field("player").get(target) as ExoPlayer
                    ready = player.isPlaying && !player.isLoading && player.videoSize.width == 320
                }
                if (ready) break
                Thread.sleep(25)
            }
            check(ready) { "Player did not reach stable playback" }
            instrumentation.waitForIdleSync()
            val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(3_000).setReadTimeoutMs(3_000)
            val factory = CacheDataSource.Factory().setCache(PlaybackCache.get(context)).setUpstreamDataSourceFactory(http)
            val worker = NextEpisodePrefetcher(factory, http)
            onMain {
                field("prefetchWorker").set(target, worker)
                val scope = field("sourceScope").get(target) as CoroutineScope
                task = scope.launch(Dispatchers.IO) {
                    try {
                        runInterruptible { worker.cache(server.base + "/warm.m3u8", 180) }
                        completedNormally.set(true)
                    } catch (_: CancellationException) {
                    } catch (_: InterruptedIOException) {
                    } finally { finished.countDown() }
                }
                field("prefetchJob").set(target, task)
            }
            check(requestedSecond.await(5, TimeUnit.SECONDS)) { "Pending warm-up did not reach its second segment" }
            onMain { instrumentation.callActivityOnPause(target) }
            check(task?.isCancelled == true) { "Activity.onPause did not cancel its pending warm-up job" }
            releaseSecond.countDown()
            check(finished.await(5, TimeUnit.SECONDS)) { "Paused warm-up did not finish its IO" }
            check(!completedNormally.get() && server.count("/warm-third.ts") == 0) { "Paused warm-up continued downloading" }
            return JSONObject().put("case", "player-prefetch-pause").put("jobCancelledOnPause", true)
                .put("ioFinished", true).put("thirdSegmentRequests", 0)
        } finally {
            releaseSecond.countDown()
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            if (task != null) finished.await(5, TimeUnit.SECONDS)
            listOf("/current.mp4", "/warm.m3u8", "/warm-first.ts", "/warm-second.ts", "/warm-third.ts")
                .forEach { PlaybackCache.get(context).removeResource(server.base + it) }
            server.close()
            check(before == preferences.all) { "Player prefetch lifecycle fixture changed preferences" }
        }
    }

    fun quality(): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val releaseNext = CountDownLatch(1)
        val server = RegressionHttpServer { uri ->
            if (uri.path.endsWith("/quality-next.mp4")) releaseNext.await()
            clip
        }
        val urls = listOf("${server.base}/quality-current.mp4", "${server.base}/quality-next.mp4")
        var activity: PlayerActivity? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, ArrayList(urls))
                putExtra(PlayerActivity.EXTRA_TITLE, "实际分辨率验收")
            }) as PlayerActivity
            val target = activity
            lateinit var player: ExoPlayer
            onMain {
                player = PlayerActivity::class.java.getDeclaredField("player")
                    .apply { isAccessible = true }.get(target) as ExoPlayer
            }
            fun await(message: String, condition: () -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < end) {
                    var done = false
                    onMain { done = condition() }
                    if (done) return
                    Thread.sleep(25)
                }
                error(message)
            }
            fun findResolution(view: View): TextView? {
                if (view is TextView && view.text.toString().startsWith("分辨率")) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    findResolution(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            await("Quality fixture did not decode") {
                player.playbackState == Player.STATE_READY && player.videoSize.width == 320 && player.videoSize.height == 180
            }
            onMain {
                player.pause()
                PlayerActivity::class.java.getDeclaredMethod("showActions").apply { isAccessible = true }.invoke(target)
            }
            await("Playback controls did not show the decoded 320 x 180 resolution") {
                findResolution(target.window.decorView)?.let { it.isShown && it.text.toString().contains("320 × 180") } == true
            }
            var shown = ""
            onMain { shown = findResolution(target.window.decorView)!!.text.toString(); player.seekToNextMediaItem() }
            await("Next episode did not enter buffering") {
                player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_BUFFERING
            }
            onMain {
                val after = findResolution(target.window.decorView)?.text.toString()
                check(after == "分辨率 未知") { "Old resolution survived an episode change: $after" }
            }
            return JSONObject().put("case", "player-quality").put("displayed", shown).put("resetOnEpisodeChange", true)
        } finally {
            releaseNext.countDown()
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            urls.forEach { PlaybackCache.get(context).removeResource(it) }
            server.close()
            check(before == preferences.all) { "Quality fixture changed preferences" }
        }
    }
}
