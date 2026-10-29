package com.dpadtv.client

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 原生播放页（Media3 / ExoPlayer）。
 *
 * 按键逻辑（电视遥控器）：
 *   · OK           暂停 / 播放
 *   · 上           打开倍速菜单
 *   · 左右         快退 / 快进（短按遵循设置，按住 15 秒连发）
 *   · 下 / 菜单键   呼出底部操作栏（下集/上集/刷新/重播/倍速/解码/比例）
 *   · 返回         退出播放
 *
 * 操作栏出现时，按键交给操作栏自己处理（左右移动、OK 执行），不再触发 seek。
 *
 * 片名默认隐藏，只在暂停、操作栏打开、或出错时出现。
 * 缓冲时在「缓冲中…」下面显示实时网速（按累计字节差分计算）。
 */
class PlayerActivity : Activity() {

    private var coverUrl: String = ""

    companion object {
        const val EXTRA_URLS = "urls"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
        const val EXTRA_SEARCH_TITLE = "searchTitle"
        const val EXTRA_SOURCE_NAME = "sourceName"
        const val EXTRA_SOURCE_URL = "sourceUrl"
        const val EXTRA_VOD_ID = "vodId"
        const val EXTRA_YEAR = "year"
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val HIDE_UI_DELAY_MS = 4_000L
        private const val HIDE_ACTIONS_DELAY_MS = 6_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleText: TextView
    private lateinit var progressRow: LinearLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var seekBar: ProgressBar
    private lateinit var timeText: TextView
    private lateinit var bufferingText: TextView
    private lateinit var speedText: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var speedActionBtn: TextView
    private lateinit var actionScroll: HorizontalScrollView
    private lateinit var prefs: Prefs
    private lateinit var sourceAction: TextView
    private val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sourceDispatcher = Dispatchers.IO.limitedParallelism(4)
    private var sourceJob: Job? = null
    private var sourceDialog: AlertDialog? = null
    private var currentSource: SearchItem? = null
    private var searchTitle = ""
    private var historyEpisodeIndex = 0
    private data class SourceBackup(
        val urls: List<String>, val index: Int, val positionMs: Long, val playWhenReady: Boolean,
        val source: SearchItem?, val title: String, val cover: String, val historyIndex: Int,
    )
    private var sourceBackup: SourceBackup? = null

    private var urls: List<String> = emptyList()
    private var currentIndex = 0
    private var seriesTitle = ""
    private var baseText = ""
    private var pendingSeek = -1L
    private var hasError = false
    private var aspectMode = 0

    private val handler = Handler(Looper.getMainLooper())
    private val sourceTimeout = Runnable { restoreSource("换源等待超时，正在恢复原来源") }

    /** 网速采样：用累计字节/累计耗时做差分 */
    private val statsListener = PlaybackStatsListener(false, null)
    private var lastBytes = 0L
    private var lastMs = 0L

    private val saveTick = object : Runnable {
        override fun run() {
            persistProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val speedTick = object : Runnable {
        override fun run() {
            if (!::speedText.isInitialized || speedText.visibility != View.VISIBLE) return
            val stats = statsListener.playbackStats
            val bytes = stats?.totalBandwidthBytes ?: 0L
            val ms = stats?.totalBandwidthTimeMs ?: 0L
            if (ms > lastMs && bytes >= lastBytes) {
                val db = bytes - lastBytes
                val dm = ms - lastMs
                if (dm > 0) speedText.text = "网速 " + formatSpeed(db * 1000L / dm)
            }
            lastBytes = bytes
            lastMs = ms
            handler.postDelayed(this, 500)
        }
    }

    private val clearPendingSeek = Runnable { pendingSeek = -1L }

    private val hideUi = Runnable {
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        handler.removeCallbacks(uiTick)
        // 暂停时控制条常驻；操作栏打开时也不隐藏
        if (!paused && !isActionBarVisible()) {
            progressRow.visibility = View.GONE
        }
        updateChrome()
    }

    private val hideActions = Runnable {
        if (isActionBarVisible()) setActionBarVisible(false)
        updateChrome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        prefs = Prefs(this)
        urls = intent.getStringArrayListExtra(EXTRA_URLS)?.toList().orEmpty()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (urls.size - 1).coerceAtLeast(0))
        seriesTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        coverUrl = intent.getStringExtra(EXTRA_COVER).orEmpty()
        val legacy = PlaybackSourceRules.historyIdentity(seriesTitle)
        searchTitle = intent.getStringExtra(EXTRA_SEARCH_TITLE) ?: legacy.title
        if (!intent.hasExtra(EXTRA_SEARCH_TITLE) && urls.size == 1) historyEpisodeIndex = legacy.episodeIndex
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        if (sourceUrl.isNotEmpty()) currentSource = SearchItem(
            intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty(), intent.getStringExtra(EXTRA_VOD_ID).orEmpty(),
            searchTitle, coverUrl, "", intent.getStringExtra(EXTRA_YEAR).orEmpty(), "", sourceUrl,
        )

        val root = FrameLayout(this)
        root.setBackgroundColor(android.graphics.Color.BLACK)
        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 片名：默认隐藏，暂停 / 操作栏 / 出错时才显示
        titleText = TextView(this).apply {
            baseText = labelFor(currentIndex)
            setText(baseText)
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            setTextSize(14f)
            setPadding(48, 28, 48, 0)
            visibility = View.GONE
        }
        root.addView(
            titleText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )

        // 进度条：左播放/暂停图标 + 进度 + 时间 + 倍速标签（都不可聚焦）
        progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 20, 48, 20)
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        playPauseIcon = ImageView(this).apply {
            setPadding(0, 0, dp(12), 0)
        }
        seekBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = getDrawable(R.drawable.player_progress)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeText = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(14f)
            setPadding(24, 0, 0, 0)
        }
        progressRow.addView(
            playPauseIcon,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        progressRow.addView(seekBar)
        progressRow.addView(timeText)
        root.addView(
            progressRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // 底部操作栏（默认隐藏）
        actionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sourceAction = actionButton("换源") { showSourcePicker() }
        actionBar.addView(sourceAction)
        actionBar.addView(actionButton("下集") {
            if (player?.hasNextMediaItem() == true) {
                player?.seekToNextMediaItem()
            } else {
                toast("已是最后一集")
            }
            showActions()
        })
        actionBar.addView(actionButton("上集") {
            if (player?.hasPreviousMediaItem() == true) {
                player?.seekToPreviousMediaItem()
            } else {
                toast("已是第一集")
            }
            showActions()
        })
        actionBar.addView(actionButton("刷新") {
            player?.let {
                it.seekTo(currentIndex, it.currentPosition)
                it.prepare()
            }
            toast("已重新加载")
            showActions()
        })
        actionBar.addView(actionButton("重播") {
            player?.seekTo(currentIndex, 0L)
            player?.play()
            showActions()
        })
        speedActionBtn = actionButton("倍速 X1.00") { showSpeedMenu() }
        actionBar.addView(speedActionBtn)
        actionBar.addView(actionButton("解码") { showDecodeMenu() })
        actionBar.addView(actionButton("比例") { showAspectMenu() })

        actionScroll = HorizontalScrollView(this).apply {
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(48), 0, dp(48), 0)
            addView(actionBar)
            visibility = View.GONE
        }
        root.addView(
            actionScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = dp(48) },
        )

        // 缓冲提示 + 网速
        bufferingText = TextView(this).apply {
            setText("缓冲中…")
            setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
            setTextSize(18f)
            visibility = View.VISIBLE
        }
        speedText = TextView(this).apply {
            setText("测速中…")
            setTextColor(android.graphics.Color.parseColor("#94a3b8"))
            setTextSize(13f)
            setPadding(0, dp(6), 0, 0)
            visibility = View.VISIBLE
        }
        val bufferingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bufferingText)
            addView(speedText)
        }
        root.addView(
            bufferingBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        setContentView(root)

        if (urls.isEmpty()) {
            Toast.makeText(this, "没有播放地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .build()

        // 分片级磁盘缓存：看过的片段落盘，回拖/重看不再重复下载
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("DpadTV-Android")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(PlaybackCache.get(this))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val mediaSourceFactory =
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(cacheFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setRenderersFactory(buildRenderers())
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addAnalyticsListener(statsListener)
                exo.setPlaybackSpeed(prefs.playbackSpeed)
                exo.pauseAtEndOfMediaItems = !prefs.autoNext

                exo.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        pendingSeek = -1L
                        handler.removeCallbacks(clearPendingSeek)
                        currentIndex = exo.currentMediaItemIndex
                        hasError = false
                        baseText = labelFor(currentIndex) + currentSource?.let { " · ${it.sourceName}" }.orEmpty()
                        if (!hasError) {
                            titleText.text = baseText
                            titleText.setTextColor(android.graphics.Color.parseColor("#cbd5e1"))
                        }
                        bufferingText.visibility = View.VISIBLE
                        updateChrome()
                        if (urls.size > 1 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                            toast("自动播放：第 ${currentIndex + 1} 集")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val buffering = playbackState == Player.STATE_BUFFERING
                        bufferingText.visibility = if (buffering) View.VISIBLE else View.GONE
                        speedText.visibility = if (buffering) View.VISIBLE else View.GONE
                        handler.removeCallbacks(speedTick)
                        if (buffering) {
                            lastBytes = 0L
                            lastMs = 0L
                            handler.post(speedTick)
                        }
                        if (playbackState == Player.STATE_ENDED && sourceBackup == null) {
                            prefs.clearProgress(currentUrl())
                        }
                        updateChrome()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseIcon()
                        updateChrome()
                        if (!isPlaying) showProgress()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (sourceBackup != null) {
                            restoreSource("该来源无法播放，正在恢复原来源")
                            return
                        }
                        val msg = if (error.errorCodeName.contains("DECODER", true)) {
                            "解码失败：这个片源可能是 H.265 / 不支持的编码 · 按下键换源"
                        } else {
                            "播放失败：${error.errorCodeName} · 按下键换源"
                        }
                        hasError = true
                        baseText = msg
                        titleText.text = msg
                        titleText.setTextColor(android.graphics.Color.parseColor("#fca5a5"))
                        bufferingText.visibility = View.GONE
                        speedText.visibility = View.GONE
                        updateChrome()
                    }

                    override fun onRenderedFirstFrame() {
                        if (sourceBackup != null) {
                            sourceBackup = null
                            handler.removeCallbacks(sourceTimeout)
                            sourceAction.isEnabled = true
                            toast("已切换到 ${currentSource?.sourceName} · 第 ${currentIndex + 1} 集")
                        }
                    }
                })

                val items = urls.map { MediaItem.fromUri(it) }
                val resume = if (prefs.resumeEnabled) prefs.progressOf(currentUrl()) else 0L
                val threshold = prefs.resumeThresholdSeconds * 1000L
                exo.setMediaItems(items, currentIndex, if (resume > threshold) resume else 0L)
                exo.prepare()
                if (resume > threshold) {
                    toast("从上次位置继续（${formatTime(resume)}）")
                }
                exo.playWhenReady = true
            }

        updateSpeedAction()
        if (kotlin.math.abs(prefs.playbackSpeed - 1f) > 0.01f) {
            toast("当前倍速 ${speedLabel(prefs.playbackSpeed)}（在上键菜单里可改回 X1.00）")
        }
        updatePlayPauseIcon()
        updateChrome()
    }

    /* ------------------------------------------------------------ 播放中换源 */

    private fun showSourcePicker() {
        if (searchTitle.isBlank()) { toast("缺少片名，无法查找其他来源"); return }
        val sources = prefs.sources().filter { it.enabled }
        if (sources.isEmpty()) { toast("没有启用的片源，请先到片源管理开启"); return }
        sourceJob?.cancel()
        val loading = AlertDialog.Builder(this).setTitle("查找其他来源")
            .setMessage("原视频保持当前播放状态…")
            .setNegativeButton("取消") { _, _ -> sourceJob?.cancel() }
            .setOnCancelListener { sourceJob?.cancel() }.show()
        sourceDialog = loading
        val active = currentSource
        val year = active?.year.orEmpty()
        sourceJob = sourceScope.launch {
            try {
                val candidates = mutableListOf<SourceCandidate>()
                val adapter = ArrayAdapter<String>(this@PlayerActivity, android.R.layout.simple_list_item_1)
                val episode = if (urls.size == 1) historyEpisodeIndex else currentIndex
                val pickerTitle = "选择来源 · 第 ${episode + 1} 集"
                var picker: AlertDialog? = null
                coroutineScope {
                    val lookup = this
                    val seen = mutableSetOf<Pair<String, String>>()
                    fun enqueue(items: List<SearchItem>, complete: Boolean) {
                        // 明确年份时，缺失年份的资源等完整结果后再按翻拍隔离规则判断。
                        val eligible = if (year.isNotEmpty() && !complete) items.filter { it.year.isNotEmpty() } else items
                        val matches = PlaybackSourceRules.matching(eligible, searchTitle, year)
                            .filterNot { it.sourceUrl == active?.sourceUrl && it.vodId == active?.vodId }
                        for (item in matches) {
                            if (!seen.add(item.sourceUrl to item.vodId)) continue
                            lookup.launch(sourceDispatcher) {
                                val started = System.nanoTime()
                                val detail = runInterruptible {
                                    runCatching {
                                        Api().detail(item.vodId, sources.first { it.url == item.sourceUrl })
                                    }
                                }.getOrNull()
                                val candidate = detail?.let {
                                    val resolved = item.copy(name = it.info.title.ifEmpty { item.name }, year = it.info.year.ifEmpty { item.year })
                                    if (PlaybackSourceRules.matching(listOf(resolved), searchTitle, year).isEmpty()) null
                                    else SourceCandidate(resolved, it, (System.nanoTime() - started) / 1_000_000)
                                }
                                if (candidate != null) withContext(Dispatchers.Main.immediate) {
                                    val selected = picker?.listView?.selectedItemPosition?.let { candidates.getOrNull(it) }
                                    candidates.add(candidate)
                                    candidates.sortBy { it.responseMs }
                                    adapter.setNotifyOnChange(false)
                                    adapter.clear()
                                    adapter.addAll(candidates.map {
                                        "${it.item.sourceName} · ${it.item.year.ifEmpty { "年份未知" }} · ${it.detail.episodes.size}集 · 响应${it.responseMs}ms"
                                    })
                                    adapter.notifyDataSetChanged()
                                    if (picker == null) {
                                        loading.dismiss()
                                        picker = AlertDialog.Builder(this@PlayerActivity)
                                            .setTitle("$pickerTitle · 继续查找中")
                                            .setAdapter(adapter) { _, which -> switchToSource(candidates[which]) }
                                            .setNegativeButton("取消", null)
                                            .setOnDismissListener { sourceJob?.cancel(); showActions() }.show()
                                        sourceDialog = picker
                                    } else if (selected != null) picker?.listView?.setSelection(candidates.indexOf(selected))
                                }
                            }
                        }
                    }
                    val result = runInterruptible(Dispatchers.IO) {
                        Api().search(searchTitle, sources) { partial ->
                            lookup.launch(Dispatchers.Main.immediate) { enqueue(partial.items, complete = false) }
                        }
                    }
                    enqueue(result.items, complete = true)
                }
                loading.dismiss()
                if (candidates.isEmpty()) { toast("没有找到其他可用的同片来源"); return@launch }
                picker?.setTitle(pickerTitle)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toast("查找来源失败，请稍后重试")
            } finally { loading.dismiss() }
        }
    }

    private fun switchToSource(candidate: SourceCandidate) {
        val p = player ?: return
        val targetIndex = if (urls.size == 1) historyEpisodeIndex else p.currentMediaItemIndex
        if (targetIndex !in candidate.detail.episodes.indices) {
            toast("该来源没有第 ${targetIndex + 1} 集，保留当前播放")
            return
        }
        persistProgress()
        val previous = SourceBackup(urls, p.currentMediaItemIndex, p.currentPosition, p.playWhenReady,
            currentSource, seriesTitle, coverUrl, historyEpisodeIndex)
        sourceBackup = previous
        sourceAction.isEnabled = false
        urls = candidate.detail.episodes
        currentSource = candidate.item
        seriesTitle = candidate.item.name.trim()
        coverUrl = candidate.detail.info.cover.ifEmpty { coverUrl }
        historyEpisodeIndex = 0
        hasError = false
        handler.postDelayed(sourceTimeout, 15_000)
        p.setMediaItems(urls.map { MediaItem.fromUri(it) }, targetIndex, previous.positionMs)
        p.prepare()
        p.playWhenReady = previous.playWhenReady
        showProgress()
    }

    private fun restoreSource(message: String?, playWhenReady: Boolean = player?.playWhenReady ?: false) {
        val previous = sourceBackup ?: return
        sourceBackup = null
        handler.removeCallbacks(sourceTimeout)
        sourceAction.isEnabled = true
        urls = previous.urls
        currentSource = previous.source
        seriesTitle = previous.title
        coverUrl = previous.cover
        historyEpisodeIndex = previous.historyIndex
        player?.apply {
            setMediaItems(urls.map { MediaItem.fromUri(it) }, previous.index, previous.positionMs)
            prepare()
            this.playWhenReady = playWhenReady
        }
        if (message != null) toast(message)
    }

    /* ------------------------------------------------------------ 显示逻辑 */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 片名可见性：出错 / 暂停 / 控制条或操作栏显示中 */
    private fun updateChrome() {
        if (!::titleText.isInitialized) return
        val p = player
        val paused = p != null && !p.playWhenReady && p.playbackState != Player.STATE_IDLE
        val chromeVisible = (::progressRow.isInitialized && progressRow.visibility == View.VISIBLE) ||
            isActionBarVisible()
        titleText.visibility = if (hasError || paused || chromeVisible) View.VISIBLE else View.GONE
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0) {
            seekBar.progress = (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            // 已缓冲部分用白色画在蓝色之后（secondaryProgress）
            seekBar.secondaryProgress = (p.bufferedPosition * 1000 / dur).toInt().coerceIn(0, 1000)
            timeText.text = formatTime(p.currentPosition) + " / " + formatTime(dur)
        } else {
            seekBar.progress = 0
            seekBar.secondaryProgress = 0
            timeText.text = formatTime(p.currentPosition) + " / --:--"
        }
        updatePlayPauseIcon()
    }

    /** 用 Canvas 画播放/暂停图标，省掉两张 png */
    private fun drawPauseIcon(paused: Boolean): android.graphics.Bitmap {
        val size = dp(16)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#e5e7eb")
            style = android.graphics.Paint.Style.FILL
        }
        if (paused) {
            val w = size * 0.20f
            val gap = size * 0.18f
            val top = size * 0.10f
            c.drawRect(size / 2f - gap / 2f - w, top, size / 2f - gap / 2f, size - top, paint)
            c.drawRect(size / 2f + gap / 2f, top, size / 2f + gap / 2f + w, size - top, paint)
        } else {
            val path = android.graphics.Path()
            path.moveTo(size * 0.26f, size * 0.12f)
            path.lineTo(size * 0.84f, size * 0.5f)
            path.lineTo(size * 0.26f, size * 0.88f)
            path.close()
            c.drawPath(path, paint)
        }
        return bmp
    }

    private fun updatePlayPauseIcon() {
        if (!::playPauseIcon.isInitialized) return
        val playing = player?.isPlaying == true
        playPauseIcon.setImageBitmap(drawPauseIcon(paused = playing))
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0L) return "测速中…"
        val kbps = bytesPerSecond / 1000.0
        return if (kbps < 1000) "${kbps.toInt()} KB/s"
        else String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1000.0)
    }

    /* ------------------------------------------------------------ 控制条 */

    private fun showProgress() {
        progressRow.visibility = View.VISIBLE
        updateProgress()
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        if (!isActionBarVisible()) {
            handler.postDelayed(hideUi, HIDE_UI_DELAY_MS)
        }
        updateChrome()
    }

    private fun isActionBarVisible(): Boolean =
        ::actionScroll.isInitialized && actionScroll.visibility == View.VISIBLE

    private fun setActionBarVisible(visible: Boolean) {
        if (!::actionScroll.isInitialized) return
        actionScroll.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            actionBar.clearFocus()
        }
    }

    /** 下键 / 菜单键：呼出操作栏，焦点落到第一个按钮 */
    private fun showActions() {
        setActionBarVisible(true)
        progressRow.visibility = View.VISIBLE
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(uiTick)
        handler.post(uiTick)
        updateChrome()
        if (actionBar.childCount > 0) {
            actionBar.post {
                actionBar.getChildAt(0).requestFocus()
            }
        }
        handler.removeCallbacks(hideActions)
        handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFFE5E7EB.toInt())
        setTextSize(14f)
        setPadding(dp(22), dp(12), dp(22), dp(12))
        val normal = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF2B3A52.toInt())
            cornerRadius = dp(8).toFloat()
        }
        val focused = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF3E7FD4.toInt())
            cornerRadius = dp(8).toFloat()
        }
        background = normal
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { v, has -> v.background = if (has) focused else normal }
        setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.rightMargin = dp(10)
        layoutParams = lp
    }

    /** 倍速显示成 X1.00 这种形式（大写 X + 两位小数） */
    private fun speedLabel(v: Float): String =
        "X" + String.format(java.util.Locale.US, "%.2f", v)

    private fun updateSpeedAction() {
        if (!::speedActionBtn.isInitialized) return
        val s = player?.playbackParameters?.speed ?: prefs.playbackSpeed
        speedActionBtn.text = "倍速 " + speedLabel(s)
    }

    private fun showSpeedMenu() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { speedLabel(it) }
        val cur = speeds.indexOfFirst {
            kotlin.math.abs(it - (player?.playbackParameters?.speed ?: 1f)) < 0.01f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setSingleChoiceItems(labels.toTypedArray(), cur.coerceAtLeast(-1)) { d, which ->
                player?.setPlaybackSpeed(speeds[which])
                prefs.playbackSpeed = speeds[which]
                updateSpeedAction()
                d.dismiss()
                showProgress()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDecodeMenu() {
        val names = arrayOf("自动（推荐）", "强制软解", "强制硬解")
        android.app.AlertDialog.Builder(this)
            .setTitle("解码方式（下次打开视频生效）")
            .setSingleChoiceItems(names, prefs.decodeMode.coerceIn(0, 2)) { d, which ->
                prefs.decodeMode = which
                d.dismiss()
                toast("已设为：${names[which]}，重新打开视频生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAspectMenu() {
        val names = arrayOf("适应屏幕", "裁切铺满", "拉伸铺满")
        android.app.AlertDialog.Builder(this)
            .setTitle("显示比例")
            .setSingleChoiceItems(names, aspectMode) { d, which ->
                aspectMode = which
                playerView.resizeMode = when (which) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解码方式：0 自动 / 1 强制软解 / 2 强制硬解 */
    private fun buildRenderers(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val mode = prefs.decodeMode
        if (mode != 0) {
            factory.setMediaCodecSelector { mime, secure, tunneling ->
                val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                if (mode == 1) {
                    all.filterNot { it.hardwareAccelerated }
                } else {
                    all.filter { it.hardwareAccelerated }.ifEmpty { all }
                }
            }
        }
        return factory
    }

    /* ------------------------------------------------------------ 进度与退出 */

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        val base = if (pendingSeek >= 0) pendingSeek else p.currentPosition
        val raw = base + deltaMs
        val target = if (dur > 0) raw.coerceIn(0, dur) else raw.coerceAtLeast(0)
        pendingSeek = target
        p.seekTo(target)
        handler.removeCallbacks(clearPendingSeek)
        handler.postDelayed(clearPendingSeek, 1_500)
        showProgress()
    }

    private fun currentUrl(): String = urls.getOrNull(currentIndex).orEmpty()

    private fun labelFor(index: Int): String {
        if (urls.size <= 1) return seriesTitle.ifEmpty { "正在播放" }
        return "$seriesTitle · 第 ${index + 1}/${urls.size} 集"
    }

    private fun persistProgress() {
        if (sourceBackup != null) return
        val p = player ?: return
        if (p.isPlaying || p.playbackState == Player.STATE_READY) {
            val url = currentUrl()
            if (url.isEmpty()) return
            prefs.saveProgress(url, p.currentPosition)
            if (p.currentPosition > 5_000L) {
                prefs.upsertHistory(
                    HistoryEntry(
                        title = labelFor(currentIndex),
                        playUrl = url,
                        positionMs = p.currentPosition,
                        durationMs = p.duration.coerceAtLeast(0L),
                        updatedAt = System.currentTimeMillis(),
                        cover = coverUrl,
                    ),
                )
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)

        // 操作栏打开时：按键全部交给菜单（按钮可聚焦、可点击）
        if (isActionBarVisible()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                handler.removeCallbacks(hideActions)
                handler.postDelayed(hideActions, HIDE_ACTIONS_DELAY_MS)
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_M,
                    -> {
                        hideActions.run()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // 已经在操作栏上，再按下键就收起
                        hideActions.run()
                        return true
                    }
                }
            }
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            -> {
                if (p.isPlaying) p.pause() else p.play()
                showProgress()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showSpeedMenu()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showActions()
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_M -> {
                showActions()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(if (event.repeatCount > 0) 15_000L else prefs.seekStepSeconds * 1000L)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(if (event.repeatCount > 0) -15_000L else -prefs.seekStepSeconds * 1000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    showProgress()
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (p.hasPreviousMediaItem()) {
                    p.seekToPreviousMediaItem()
                    showProgress()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
    }

    override fun onPause() {
        persistProgress()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        sourceJob?.cancel()
        sourceDialog?.dismiss()
        restoreSource(null, playWhenReady = false)
        handler.removeCallbacks(saveTick)
        player?.pause()
    }

    override fun onDestroy() {
        sourceScope.cancel()
        handler.removeCallbacks(sourceTimeout)
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(uiTick)
        handler.removeCallbacks(hideUi)
        handler.removeCallbacks(speedTick)
        handler.removeCallbacks(hideActions)
        handler.removeCallbacks(clearPendingSeek)
        player?.release()
        player = null
        super.onDestroy()
    }
}
