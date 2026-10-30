package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Local catalogue fixture; does not replace sources, history or other user preferences. */
class RecommendPrefetchProbe(private val instrumentation: Instrumentation) {
    fun run(caseId: String): JSONObject {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("dpadtv_native", 0)
        val before = preferences.all.toMap()
        val releasePage = CountDownLatch(1)
        val requestedPage = CountDownLatch(1)
        val poster = ByteArrayOutputStream().let { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        lateinit var server: RegressionHttpServer
        server = RegressionHttpServer { uri ->
            if (uri.path.startsWith("/image/")) poster else {
                val params = uri.rawQuery.orEmpty().split('&').associate {
                    val pair = it.split('=', limit = 2)
                    pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
                val start = params.getValue("page_start").toInt()
                if (start == 48 || caseId == "prefetch-initial") { requestedPage.countDown(); releasePage.await() }
                val response = JSONObject().put("subjects", JSONArray().apply {
                    repeat(if (start < 96) 48 else 0) { index ->
                        put(JSONObject().put("title", "Fixture ${start + index}")
                            .put("cover", server.base + "/image/${start + index}.png").put("rate", "8.0"))
                    }
                }).toString()
                (if (caseId == "prefetch-retry" && start == 48 && server.count("/subjects") == 1)
                    "invalid-json" else response).toByteArray()
            }
        }
        var activity: MainActivity? = null
        var loader: ImageLoader? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun invoke(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
        fun findGrid(view: View): GridView? {
            if (view is GridView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findGrid(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun grid() = findGrid(field("root").get(activity) as View) ?: error("Recommendation grid missing")
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
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val api = Api(doubanEndpoint = server.base + "/subjects")
            onMain {
                for (name in listOf("job", "recommendJob")) {
                    val f = MainActivity::class.java.declaredFields.firstOrNull { it.name == name }
                    (f?.apply { isAccessible = true }?.get(activity) as? Job)?.cancel()
                }
                invoke("dismissProgress")
                field("api").set(activity, api)
                loader = ImageLoader(api)
                field("imageLoader").set(activity, loader)
                field("recommendItems").set(activity, List(if (caseId == "prefetch-initial") 0 else 48) { index ->
                    DoubanItem("Fixture $index", server.base + "/image/$index.png", "8.0")
                })
                field("recommendPage").setInt(activity, 0)
                field("noMoreRecommend").setBoolean(activity, false)
                field("isLoadingMore").setBoolean(activity, false)
                MainActivity::class.java.getDeclaredMethod("showRecommend", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, false)
            }
            if (caseId == "prefetch-initial") {
                check(requestedPage.await(5, TimeUnit.SECONDS))
                onMain { (field("homeBar").get(activity) as LinearLayout).getChildAt(0).requestFocus() }
                releasePage.countDown()
                await("Initial catalogue did not load") { grid().adapter.count == 48 && grid().childCount > 0 }
                var focused = false
                onMain { focused = grid().hasFocus() }
                check(focused) { "Initial catalogue left focus on navigation instead of the poster grid" }
                return JSONObject().put("case", caseId).put("initialItems", 48).put("gridFocused", true)
            }
            await("Fixture grid did not lay out") { grid().childCount > 0 && grid().lastVisiblePosition >= 6 }
            lateinit var originalGrid: GridView
            var lastVisible = 0
            var visibleCount = 0
            onMain {
                originalGrid = grid()
                lastVisible = originalGrid.lastVisiblePosition
                visibleCount = lastVisible - originalGrid.firstVisiblePosition + 1
            }
            if (caseId == "prefetch-covers") {
                val target = (lastVisible + 1 + visibleCount).coerceAtMost(47)
                check(target > lastVisible)
                await("The second offscreen cover viewport was not preloaded") {
                    server.count("/image/$target.png") == 1
                }
                lateinit var image: ImageView
                onMain {
                    val cell = originalGrid.adapter.getView(target, null, originalGrid) as ViewGroup
                    image = cell.getChildAt(0) as ImageView
                }
                await("Preloaded cover was not decoded by the real card binding") {
                    (image.drawable as? BitmapDrawable)?.bitmap?.width == 2
                }
                check(server.count("/image/$target.png") == 1) { "Binding downloaded a preloaded cover again" }
                return JSONObject().put("case", caseId).put("visibleCount", visibleCount)
                    .put("offscreenIndex", target).put("requests", 1).put("decoded", true)
            }
            onMain { originalGrid.requestFocus(); originalGrid.setSelection(24) }
            await("Grid did not select the prefetch boundary") { originalGrid.selectedItemPosition == 24 }
            check(requestedPage.await(5, TimeUnit.SECONDS)) { "Next page was not requested before reaching the end" }
            var blocking = false
            var selected = -1
            var firstVisible = -1
            var pending: Job? = null
            onMain {
                blocking = (field("busyDialog").get(activity) as? android.app.AlertDialog)?.isShowing == true
                selected = originalGrid.selectedItemPosition
                firstVisible = originalGrid.firstVisiblePosition
                pending = MainActivity::class.java.declaredFields.filter { it.name in listOf("recommendJob", "job") }
                    .mapNotNull { it.isAccessible = true; it.get(activity) as? Job }.firstOrNull { it.isActive }
            }
            check(!blocking) { "Background pagination displayed a blocking loading dialog" }
            if (caseId == "prefetch-background") {
                onMain { instrumentation.callActivityOnPause(activity) }
                releasePage.countDown()
                await("Paused page task did not finish") { pending?.isCompleted == true }
                var count = 0
                onMain { count = originalGrid.adapter.count }
                check(count == 48) { "Paused page still appended a prefetch" }
                onMain { instrumentation.callActivityOnResume(activity) }
                await("Foreground did not resume prefetch") { grid().adapter.count == 96 }
                return JSONObject().put("case", caseId).put("pausedWithoutAppend", true).put("resumedItems", 96)
            }
            if (caseId == "prefetch-scope") {
                onMain { invoke("showSetup") }
                releasePage.countDown()
                await("Cancelled page task did not finish") { pending?.isCompleted == true }
                var screen = ""
                onMain { screen = field("currentScreen").get(activity) as String }
                check(screen == "setup") { "A stale prefetch replaced the page after navigation: $screen" }
                return JSONObject().put("case", caseId).put("stalePageIgnored", true)
            }
            releasePage.countDown()
            if (caseId == "prefetch-retry") {
                await("Failed prefetch was not retained for explicit retry") {
                    field("recommendFailedPage").getInt(activity) == 1 && !field("isLoadingMore").getBoolean(activity)
                }
                onMain { originalGrid.setSelection(47) }
                await("Retry boundary was not selected") { originalGrid.selectedItemPosition == 47 }
                check(originalGrid.adapter.count == 48 && server.count("/subjects") == 1)
                onMain {
                    originalGrid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
                    originalGrid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
                }
                await("Down at the list end did not retry the next page") { grid().adapter.count == 96 }
                check(server.count("/subjects") == 2)
                return JSONObject().put("case", caseId).put("retainedItemsAfterFailure", 48)
                    .put("itemsAfterDownRetry", 96).put("requests", 2)
            }
            await("Prefetched page was not appended") { grid().adapter.count == 96 }
            var sameGrid = false
            var sameSelection = false
            var sameScroll = false
            onMain {
                sameGrid = grid() === originalGrid
                sameSelection = grid().selectedItemPosition == selected && grid().hasFocus()
                sameScroll = grid().firstVisiblePosition == firstVisible
            }
            check(sameGrid && sameSelection && sameScroll) { "Pagination rebuilt or moved the focused grid" }
            check(server.count("/subjects") == 1) { "Scrolling recursively fetched more than one next page" }
            return JSONObject().put("case", caseId).put("items", 96).put("blockingDialog", false)
                .put("sameGrid", true).put("selectionPreserved", true).put("scrollPreserved", true)
        } finally {
            releasePage.countDown()
            onMain { activity?.finish() }
            instrumentation.waitForIdleSync()
            loader?.let {
                val pool = ImageLoader::class.java.getDeclaredField("pool").apply { isAccessible = true }.get(it) as ExecutorService
                pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS)
            }
            server.close()
            check(before == preferences.all) { "Recommendation fixture changed user preferences" }
        }
    }
}
