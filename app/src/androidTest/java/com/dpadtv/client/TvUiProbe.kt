package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.ImageButton
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import kotlinx.coroutines.Job
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject

/** Layout and remote regressions on the disposable TV emulator, with local test media. */
class TvUiProbe(private val instrumentation: Instrumentation) {
    fun navigation(): JSONObject {
        check(android.os.Build.MODEL == "AOSP TV on x86") { "UI fixture is emulator-only" }
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val saved = AppBackup.decode(prefs.exportBackup())
        var activity: MainActivity? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        try {
            prefs.decodeMode = 0
            instrumentation.setInTouchMode(false)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val target = activity
            lateinit var bar: LinearLayout
            lateinit var grid: GridView
            onMain {
                bar = field("homeBar").get(target) as LinearLayout
                check(bar.findViewWithTag<View>("search") is ImageButton) { "Search is still a text button" }
                check(bar.findViewWithTag<View>("setup") is ImageButton) { "Settings is still a text button" }
                check(bar.indexOfChild(bar.findViewWithTag("favorites")) + 1 == bar.indexOfChild(bar.findViewWithTag("history")))
                (field("recommendJob").get(target) as? Job)?.cancel()
                field("recommendItems").set(target, List(12) { DoubanItem("导航测试 $it", "", "") })
                grid = field("recommendGrid").get(target) as GridView
                (grid.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                grid.requestFocus(); grid.setSelection(6)
            }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_BACK)
            onMain {
                check(bar.hasFocus()) { "Back exited instead of returning to the top menu" }
                check(field("recommendGrid").get(target) === grid) { "Back rebuilt the poster wall" }
            }
            screenshot("ui-home")
            onMain { bar.findViewWithTag<View>("setup").requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            onMain {
                val root = target.window.decorView
                for (name in listOf("片源管理", "播放设置", "首页导航", "导出备份", "恢复备份", "清理缓存")) {
                    val label = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == name }
                    val rect = Rect()
                    check(label.getGlobalVisibleRect(rect) && rect.height() >= label.height - 2) {
                        "Setting card clipped: $name, view=${label.height}, visible=${rect.height()}"
                    }
                }
            }
            screenshot("ui-settings")
            onMain {
                val label = descendants(target.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "播放设置" }
                (label.parent as View).requestFocus()
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onMain {
                val list = descendants(target.window.decorView).filterIsInstance<ListView>().single()
                list.requestFocus(); list.setSelection(4)
            }
            instrumentation.waitForIdleSync()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(prefs.decodeMode == 1) { "Remote selection did not apply the decode setting" }
            screenshot("ui-playsettings")
            key(KeyEvent.KEYCODE_BACK) // Playback settings -> settings cards.
            key(KeyEvent.KEYCODE_BACK) // Cards -> the settings icon in the top menu.
            onMain { check((field("homeBar").get(target) as LinearLayout).hasFocus()) }
            onMain { (field("homeBar").get(target) as LinearLayout).findViewWithTag<View>("sources").requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_BACK)
            onMain {
                check(field("currentScreen").get(target) == "sources") { "Global source tab was treated as a settings subpage" }
                check((field("homeBar").get(target) as LinearLayout).hasFocus())
            }
            key(KeyEvent.KEYCODE_BACK) // At the source top menu -> launcher.
            await("Second Back did not leave the app") { !target.hasWindowFocus() || target.isFinishing }
            onMain {
                prefs.saveHomeBarOrder(listOf("tv", "history", "search"))
                check(prefs.homeBarOrder() == listOf("tv", "favorites", "history", "search"))
            }
            return JSONObject().put("case", "tv-navigation").put("iconNavigation", true)
                .put("backKeepsContentThenExits", true).put("sixSettingCardsVisible", true)
                .put("remoteSettingApplied", true).put("oldNavigationOrderPreserved", true)
        } finally {
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            prefs.restoreBackup(saved)
        }
    }

    fun favorites(legacyHistory: Boolean = false): JSONObject {
        check(android.os.Build.MODEL == "AOSP TV on x86") { "UI fixture is emulator-only" }
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val saved = AppBackup.decode(prefs.exportBackup())
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val releaseHistory = CountDownLatch(if (legacyHistory) 1 else 0)
        lateinit var server: RegressionHttpServer
        server = RegressionHttpServer { uri ->
            if (uri.path.startsWith("/cms")) {
                if (uri.rawQuery.orEmpty().contains("ids=")) releaseHistory.await()
                JSONObject().put("pagecount", 1).put("list", JSONArray().put(
                    JSONObject().put("vod_id", "favorite-fixture").put("vod_name", "收藏测试剧").put("vod_year", "2026")
                        .put("vod_play_url", "第1集\$${server.base}/one.mp4#第2集\$${server.base}/two.mp4")
                )).toString().toByteArray()
            } else clip
        }
        var playback: PlayerActivity? = null
        var main: MainActivity? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun playerField(name: String) = PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            prefs.saveSources(listOf(SourceConfig("来源 A", server.base + "/cms-a"), SourceConfig("来源 B", server.base + "/cms-b")))
            prefs.removeFavorite(FavoriteEntry("收藏测试剧", "", "2026", 0))
            prefs.removeFavorite(FavoriteEntry("收藏测试剧", "", "", 0))
            playback = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, if (legacyHistory) arrayListOf(server.base + "/one.mp4")
                    else arrayListOf(server.base + "/one.mp4", server.base + "/two.mp4"))
                putExtra(PlayerActivity.EXTRA_TITLE, if (legacyHistory) "收藏测试剧 · 第 1/2 集" else "收藏测试剧")
                if (legacyHistory) putExtra(PlayerActivity.EXTRA_FROM_HISTORY, true)
                else {
                    putExtra(PlayerActivity.EXTRA_SEARCH_TITLE, "收藏测试剧")
                    putExtra(PlayerActivity.EXTRA_YEAR, "2026")
                }
            }) as PlayerActivity
            val target = playback
            await("Local playback did not render") { (playerField("player").get(target) as ExoPlayer).videoSize.width == 320 }
            lateinit var player: ExoPlayer
            lateinit var star: TextView
            onMain {
                player = playerField("player").get(target) as ExoPlayer
                player.pause()
                PlayerActivity::class.java.getDeclaredMethod("showActions").apply { isAccessible = true }.invoke(target)
            }
            instrumentation.waitForIdleSync()
            onMain { star = playerField("favoriteAction").get(target) as TextView; star.requestFocus() }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            if (legacyHistory) {
                check(prefs.isFavorite("收藏测试剧", ""))
                releaseHistory.countDown()
                await("History metadata did not update the favorite identity") {
                    playerField("favoriteYear").get(target) == "2026" && player.mediaItemCount == 2
                }
                onMain { check(star.text == "已收藏") }
            }
            check(prefs.isFavorite("收藏测试剧", "2026"))
            onMain { player.seekToNextMediaItem() }
            await("Episode did not change") { player.currentMediaItemIndex == 1 }
            check(prefs.favorites().count { it.title == "收藏测试剧" } == 1) { "Episodes created duplicate favorites" }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            check(!prefs.isFavorite("收藏测试剧", "2026"))
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            onMain {
                check(star.text == "已收藏")
                check(playerField("player").get(target) === player && !player.playWhenReady) { "Favorite restarted playback" }
            }
            screenshot("ui-player-favorite")
            onMain { target.finish() }
            instrumentation.waitForIdleSync()
            main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val home = main
            onMain {
                (MainActivity::class.java.getDeclaredField("recommendJob").apply { isAccessible = true }.get(home) as? Job)?.cancel()
                MainActivity::class.java.getDeclaredMethod("showFavorites", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(home, 0)
            }
            instrumentation.waitForIdleSync()
            screenshot("ui-favorites")
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            await("Favorite did not search both enabled sources") { server.count("/cms-a") > 0 && server.count("/cms-b") > 0 }
            key(KeyEvent.KEYCODE_BACK)
            onMain {
                val screen = MainActivity::class.java.getDeclaredField("currentScreen").apply { isAccessible = true }.get(home)
                check(screen == "favorites") { "Search did not return to the favorite shelf" }
            }
            val restored = AppBackup.decode(prefs.exportBackup())
            check(restored.favorites.any { it.title == "收藏测试剧" && it.year == "2026" })
            return JSONObject().put("case", if (legacyHistory) "tv-favorites-history" else "tv-favorites")
                .put("legacyHistory", legacyHistory).put("toggleWorks", true).put("seriesDeduplicated", true)
                .put("playerAndPausePreserved", true).put("bothSourcesSearched", true)
                .put("backReturnsToFavorites", true).put("includedInBackup", true)
        } finally {
            releaseHistory.countDown()
            onMain { playback?.finish(); main?.finish() }
            instrumentation.waitForIdleSync()
            prefs.restoreBackup(saved)
            server.close()
        }
    }

    fun dialogs(): JSONObject {
        check(android.os.Build.MODEL == "AOSP TV on x86") { "UI fixture is emulator-only" }
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val saved = AppBackup.decode(prefs.exportBackup())
        val clip = instrumentation.context.assets.open("source-switch-fixture.mp4").use { it.readBytes() }
        val server = RegressionHttpServer { clip }
        var activity: PlayerActivity? = null
        val layouts = JSONArray()
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        try {
            activity = instrumentation.startActivitySync(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putStringArrayListExtra(PlayerActivity.EXTRA_URLS, arrayListOf(server.base + "/ui.mp4"))
                putExtra(PlayerActivity.EXTRA_TITLE, "界面验证短片")
            }) as PlayerActivity
            val target = activity
            for ((method, labels) in listOf(
                "showDecodeMenu" to listOf("自动（推荐）", "强制软解", "强制硬解"),
                "showAspectMenu" to listOf("适应屏幕", "裁切铺满", "拉伸铺满"),
            )) {
                onMain {
                    PlayerActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(target)
                }
                instrumentation.waitForIdleSync()
                var visible = 0
                val bounds = JSONArray()
                onMain {
                    WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<TextView>()
                        .filter { it.text.toString() in labels }.forEach { row ->
                            val rect = Rect()
                            val shown = row.getGlobalVisibleRect(rect) && rect.height() >= row.height - 2
                            if (shown) visible++
                            bounds.put(JSONObject().put("label", row.text).put("height", row.height)
                                .put("visible", shown).put("bounds", rect.toShortString()))
                        }
                }
                screenshot(method)
                layouts.put(JSONObject().put("menu", method).put("visibleRows", visible).put("rows", bounds))
                context.openFileOutput("tv-dialog-layout.json", 0).bufferedWriter().use { it.write(layouts.toString(2)) }
                check(visible == 3) { "$method shows only $visible complete options" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
            }
            return JSONObject().put("case", "tv-dialogs").put("layouts", layouts)
        } finally {
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            prefs.restoreBackup(saved)
            server.close()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun key(code: Int) {
        await("App window did not regain focus before key $code") {
            WindowInspector.getGlobalWindowViews().any {
                it.hasWindowFocus() && it.context.packageName == instrumentation.targetContext.packageName
            }
        }
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            Thread.sleep(25)
        }
        error(message)
    }

    private fun screenshot(name: String) {
        await("Screenshot window has not acquired focus") {
            WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() &&
                it.context.packageName == instrumentation.targetContext.packageName }
        }
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val window = WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
            window.viewTreeObserver.registerFrameCommitCallback {
                window.postOnAnimation { committed.countDown() }
            }
            window.invalidate()
        }
        check(committed.await(3, TimeUnit.SECONDS)) { "UI frame was not committed before screenshot" }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        instrumentation.targetContext.openFileOutput("$name.png", 0).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
