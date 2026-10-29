package com.dpadtv.client

import android.app.Instrumentation
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Nine identical pending requests must share one download and all receive a bitmap. */
class ImageLoadProbe(private val instrumentation: Instrumentation) {
    fun run(): JSONObject {
        val poster = ByteArrayOutputStream().let { out ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(9)
        val bitmaps = mutableListOf<Bitmap?>()
        val loader = ImageLoader(Api())
        val server = RegressionHttpServer {
            requested.countDown()
            release.await()
            poster
        }
        try {
            instrumentation.runOnMainSync {
                repeat(9) {
                    loader.load(server.base + "/poster.png", 2, 2) { bitmap ->
                        bitmaps.add(bitmap)
                        completed.countDown()
                    }
                }
            }
            check(requested.await(10, TimeUnit.SECONDS)) { "Poster download did not start" }
            release.countDown()
            check(completed.await(15, TimeUnit.SECONDS)) { "Missing callbacks: ${completed.count} remaining" }
            check(bitmaps.all { it != null && it.width == 2 && it.height == 2 }) {
                "All nine callbacks must receive a decoded 2x2 bitmap"
            }
            val requests = server.count("/poster.png")
            check(requests == 1) { "Expected one shared download, observed $requests requests for nine callbacks" }
            return JSONObject().put("callbacks", bitmaps.size).put("requests", requests)
                .put("width", 2).put("height", 2)
        } finally {
            release.countDown()
            // Drain queued downloads before closing the local server, including assertion failures.
            val pool = ImageLoader::class.java.getDeclaredField("pool").apply { isAccessible = true }
                .get(loader) as ExecutorService
            pool.shutdown()
            pool.awaitTermination(20, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            server.close()
            instrumentation.runOnMainSync { loader.clearCache() }
            bitmaps.filterNotNull().distinct().forEach { it.recycle() }
        }
    }
}
