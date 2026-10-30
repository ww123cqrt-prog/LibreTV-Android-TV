package com.dpadtv.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * 极简海报图加载器（不引 Coil/Glide）。
 *
 * 三件事必须做对，否则电视盒子上会立刻出问题：
 *   1. 由 Api 直接取封面；豆瓣请求头由同一传输层处理，无需电脑图片代理
 *   2. inSampleSize 降采样到目标尺寸附近 —— 不降采样的话几十张原图能把老设备的内存打爆
 *   3. 在 IO 线程下载、主线程交付；ImageView 复用时要校验 URL，避免GridView 滚动串图
 */
class ImageLoader(private val api: Api) {

    private val cache = LruCache<String, Bitmap>(160)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val preloadQueue = ArrayDeque<String>()
    private val preloadAttempted = mutableSetOf<String>()
    private var preloadActive = false
    private var preloadWidth = 0
    private var preloadHeight = 0

    /** Main-thread viewport queue; one warm-up leaves two workers for visible images. */
    fun preload(urls: List<String>, reqWidth: Int, reqHeight: Int) {
        val wanted = urls.filter { it.isNotEmpty() }.associateBy { "$it@${reqWidth}x$reqHeight" }
        preloadAttempted.retainAll(wanted.keys)
        preloadQueue.clear()
        wanted.forEach { (key, url) ->
            if (key !in preloadAttempted && cache.get(key) == null && key !in inFlight) preloadQueue.addLast(url)
        }
        preloadWidth = reqWidth
        preloadHeight = reqHeight
        preloadNext()
    }

    fun clearPreload() {
        preloadQueue.clear()
        preloadAttempted.clear()
    }

    private fun preloadNext() {
        if (preloadActive || preloadQueue.isEmpty()) return
        val url = preloadQueue.removeFirst()
        preloadAttempted.add("$url@${preloadWidth}x$preloadHeight")
        preloadActive = true
        load(url, preloadWidth, preloadHeight) {
            preloadActive = false
            preloadNext()
        }
    }

    /** Called by UI bindings on the main thread. */
    fun load(url: String, reqWidth: Int, reqHeight: Int, onReady: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            onReady(null)
            return
        }
        val key = "$url@${reqWidth}x$reqHeight"
        cache.get(key)?.let {
            onReady(it)
            return
        }
        inFlight[key]?.let {
            it.add(onReady)
            return
        }
        inFlight[key] = mutableListOf(onReady)
        pool.execute {
            val bmp = download(url, reqWidth, reqHeight)
            main.post {
                if (bmp != null) cache.put(key, bmp)
                inFlight.remove(key)?.forEach { it(bmp) }
            }
        }
    }

    private fun download(url: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bytes = api.imageBytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
    /** 清空内存中的海报缓存，返回清掉的张数 */
    fun clearCache(): Int {
        val n = cache.size()
        cache.evictAll()
        return n
    }

    fun cacheSize(): Int = cache.size()
}
