package com.dpadtv.client

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SourcePickerProbe(private val instrumentation: Instrumentation) {
    fun run(unknownYear: Boolean = false): JSONObject {
        val context = instrumentation.targetContext
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        val savedSources = sp.getString("sources", null)
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val fastSearch = CountDownLatch(1)
        val fastDetail = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val title = "Stream Fixture"
        lateinit var server: RegressionHttpServer
        fun row(id: Int, year: String): JSONObject = JSONObject().put("vod_id", id)
            .put("vod_name", title).put("vod_year", year)
            .put("vod_play_url", "正片${'$'}${server.base}/clip.mp4")
        server = RegressionHttpServer { uri ->
            when (uri.path) {
                "/clip.mp4" -> clip
                "/slow" -> {
                    releaseSlow.await()
                    JSONObject().put("pagecount", 1).put("list",
                        if (unknownYear) JSONArray().put(row(2, "1984")) else JSONArray()).toString().toByteArray()
                }
                else -> {
                    if (uri.rawQuery.orEmpty().contains("ids=")) fastDetail.countDown() else fastSearch.countDown()
                    JSONObject().put("pagecount", 1).put("list", JSONArray()
                        .put(row(1, if (unknownYear) "" else "2026"))).toString().toByteArray()
                }
            }
        }
        var activity: PlayerActivity? = null
        try {
            Prefs(context).saveSources(listOf(SourceConfig("Fast", server.base + "/fast"), SourceConfig("Slow", server.base + "/slow")))
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, arrayListOf(server.base + "/clip.mp4"))
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SEARCH_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE_NAME, "Original")
                putExtra(PlayerActivity.EXTRA_SOURCE_URL, "https://fixture.invalid/original")
                putExtra(PlayerActivity.EXTRA_VOD_ID, "original")
                putExtra(PlayerActivity.EXTRA_YEAR, "2026")
            }) as PlayerActivity
            val target = activity
            fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
            val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val dialogField = PlayerActivity::class.java.getDeclaredField("sourceDialog").apply { isAccessible = true }
            val jobField = PlayerActivity::class.java.getDeclaredField("sourceJob").apply { isAccessible = true }
            lateinit var player: ExoPlayer
            onMain { player = playerField.get(target) as ExoPlayer; player.pause() }
            fun await(message: String, condition: () -> Boolean) {
                val end = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < end) {
                    var done = false
                    onMain { done = condition() }
                    if (done) return
                    Thread.sleep(30)
                }
                error(message)
            }
            await("Fixture not ready") { player.playbackState == Player.STATE_READY }
            val started = System.nanoTime()
            onMain {
                PlayerActivity::class.java.getDeclaredMethod("showSourcePicker").apply { isAccessible = true }.invoke(target)
            }
            if (unknownYear) {
                check(fastSearch.await(3, TimeUnit.SECONDS))
                Thread.sleep(200)
                onMain { check((dialogField.get(target) as? AlertDialog)?.listView?.adapter?.count ?: 0 == 0) }
                releaseSlow.countDown()
                await("Lookup did not finish") { (jobField.get(target) as Job).isCompleted }
                onMain { check((dialogField.get(target) as? AlertDialog)?.listView?.adapter?.count ?: 0 == 0) }
                return JSONObject().put("case", "r4-unknown").put("ambiguousYearExcluded", true)
            }
            check(fastDetail.await(3, TimeUnit.SECONDS)) { "Fast detail waited for the blocked slow search" }
            await("Fast candidate not selectable") {
                val dialog = dialogField.get(target) as? AlertDialog
                dialog?.isShowing == true && (dialog.listView?.adapter?.count ?: 0) > 0
            }
            check(releaseSlow.count == 1L) { "Slow source was already released" }
            return JSONObject().put("case", "r4").put("candidateBeforeSlowSearch", true)
                .put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
        } finally {
            releaseSlow.countDown()
            activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
            instrumentation.waitForIdleSync()
            sp.edit().putString("sources", savedSources).commit()
            PlaybackCache.get(context).removeResource(server.base + "/clip.mp4")
            server.close()
        }
    }
}
