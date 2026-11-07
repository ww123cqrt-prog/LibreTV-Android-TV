package com.dpadtv.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * LibreTV 的 HTTP 契约（均由本机实测确认，不是照文档猜的）：
 *
 *   GET  /api/status                                  -> {passwordRequired, verified, version, ...}
 *   POST /api/auth   {"password"}                     -> {"success":true} + Set-Cookie ltv_session（90 天）
 *   POST /api/search {"wd","sources":[...],"filterAdult"}
 *                                                     -> {list:[{sourceName,vodId,name,pic,typeName,
 *                                                          year,area,remarks,sourceUrl,isAdult}], failures:[]}
 *   GET  /api/detail?id=<vodId>&source=<JSON 编码>     -> {episodes:[m3u8 字符串...], videoInfo:{...}}
 *        ↑ 注意 episodes 是**纯 URL 字符串数组**，不是集对象
 *   GET  /api/douban?type&tag&pageStart&pageSize       -> 豆瓣条目（首页推荐）
 *   GET  /api/source-list?url=<订阅地址>               -> {sources:[{name,url,detail,isAdult}], liveSources:[...]}
 */
/**
 * /api/status 的返回。重点：defaultSources / defaultLiveSources 是 LibreTV 为
 * 「第三方客户端」下发的服务端配置 —— 网页版自己用 localStorage，原生端读不到那玩意，
 * 必须走这两个字段，否则就会出现「服务器明明配了片源，客户端还让你再配一遍」。
 */
data class AppStatus(
    val passwordRequired: Boolean,
    val verified: Boolean,
    val version: String,
    val defaultSources: List<SourceConfig> = emptyList(),
    val defaultLiveSources: List<String> = emptyList(),
    /** DEFAULT_SUBSCRIPTIONS：服务端预置的 SourceList 订阅链接（需再用 /api/source-list 解析） */
    val defaultSubscriptions: List<String> = emptyList(),
)

data class SourceConfig(
    val name: String,
    val url: String,
    val detail: String = "",
    val isAdult: Boolean = false,
    /** 停用的源不参与搜索 */
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("url", url)
        if (detail.isNotEmpty()) put("detail", detail)
        put("isAdult", isAdult)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): SourceConfig? {
            val url = o.optString("url", "")
            if (url.isEmpty()) return null
            return SourceConfig(
                name = o.optString("name", url.substringAfter("//").substringBefore('/')),
                url = url,
                detail = o.optString("detail", ""),
                isAdult = o.optBoolean("isAdult", false),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

data class SearchItem(
    val sourceName: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val typeName: String,
    val year: String,
    val remarks: String,
    val sourceUrl: String,
)

data class SearchGroup(val name: String, val year: String, val items: List<SearchItem>)

data class VideoInfo(
    val title: String,
    val cover: String,
    val desc: String,
    val remarks: String,
    val year: String,
    val area: String,
    val actor: String,
)

data class Detail(val episodes: List<String>, val info: VideoInfo)
data class HistoryPlaylist(val source: SearchItem, val episodes: List<String>, val index: Int)

data class DoubanItem(val title: String, val cover: String, val rating: String)

// These paths and provider order follow LibreSpark/LibreTV v2.21.2 (douban-weekly.ts).
internal enum class HomeRanking(val label: String, val path: String) {
    MOVIE_WEEKLY("电影周榜", "/v2/douban/weekly/movie"),
    TV_POPULAR("热门剧榜", "/v2/baidu/teleplay"),
    TV_CHINESE("国产剧周榜", "/v2/douban/weekly/tv_chinese"),
    TV_GLOBAL("海外剧周榜", "/v2/douban/weekly/tv_global"),
}

data class SearchResult(val items: List<SearchItem>, val failures: Int, val reports: List<CmsSourceReport> = emptyList())

data class Channel(val name: String, val url: String, val group: String, val logo: String)

class HttpError(val status: Int, val body: String) : java.io.IOException("HTTP $status")

class Api(
    baseUrl: String = "",
    private val doubanEndpoint: String = "https://movie.douban.com/j/search_subjects",
    private val rankingBases: List<String> = listOf("https://60s.crystelf.top", "https://60s.viki.moe"),
) {
    val baseUrl = baseUrl
    val isStandalone = true
    private val cms = AppleCmsClient()
    private val rankings = ConcurrentHashMap<HomeRanking, Pair<Long, List<DoubanItem>>>()
    val lastReports: List<CmsSourceReport> get() = cms.lastReports

    fun restoreCookie(value: String?) = Unit
    fun currentCookie(): String? = null
    fun status(): AppStatus = error("本应用无需配置服务器")
    fun login(password: String): AppStatus = error("本应用无需服务器登录")
    fun search(
        keyword: String,
        sources: List<SourceConfig>,
        deadlineNanos: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(12),
        onProgress: ((SearchResult) -> Unit)? = null,
    ): SearchResult = cms.search(keyword, sources, deadlineNanos, onProgress)
    fun detail(vodId: String, source: SourceConfig): Detail = cms.detail(vodId, source)

    suspend fun historyPlaylist(
        title: String, playUrl: String, known: SearchItem?, sources: List<SourceConfig>,
        dispatcher: CoroutineDispatcher,
    ): HistoryPlaylist? = withTimeoutOrNull(15_000) {
        val enabled = sources.filter { it.enabled && !it.isAdult }.associateBy { it.url }
        if (enabled.isEmpty() || title.isBlank()) return@withTimeoutOrNull null
        val candidates = if (known != null) listOf(known) else {
            val result = runInterruptible(dispatcher) { search(title, enabled.values.toList()) }
            PlaybackSourceRules.matching(result.items, title, "")
        }
        val matches = coroutineScope {
            candidates.mapNotNull { item ->
                val source = enabled[item.sourceUrl] ?: return@mapNotNull null
                if (item.vodId.isBlank()) return@mapNotNull null
                async(dispatcher) {
                    val detail = runInterruptible { runCatching { detail(item.vodId, source) }.getOrNull() }
                        ?: return@async null
                    val resolved = item.copy(
                        name = detail.info.title.ifEmpty { item.name },
                        year = detail.info.year.ifEmpty { item.year },
                    )
                    val sameTitle = PlaybackSourceRules.matching(listOf(resolved), title, known?.year.orEmpty()).isNotEmpty()
                    if (sameTitle && playUrl in detail.episodes) resolved to detail.episodes else null
                }
            }.awaitAll().filterNotNull()
        }
        // 同一份集单可能被多个资源站转载。播放顺序有冲突或地址重复时只保留原链接。
        val (source, episodes) = matches.distinctBy { it.second }.singleOrNull() ?: return@withTimeoutOrNull null
        val index = episodes.indexOf(playUrl)
        if (index == episodes.lastIndexOf(playUrl)) HistoryPlaylist(source, episodes, index) else null
    }

    fun testSource(url: String): Pair<Boolean, Int> = cms.testSource(url)
    fun douban(type: String, tag: String, pageStart: Int, pageSize: Int): List<DoubanItem> {
        val params = mapOf("type" to if (type == "movie") "movie" else "tv", "tag" to tag,
            "sort" to "recommend", "page_start" to pageStart.toString(), "page_limit" to pageSize.toString())
        val query = params.entries.joinToString("&") { (key, value) -> "$key=" + URLEncoder.encode(value, "UTF-8") }
        val root = JSONObject(directBytes("$doubanEndpoint?$query", "https://movie.douban.com/", "application/json").toString(Charsets.UTF_8))
        val subjects = root.getJSONArray("subjects")
        return (0 until subjects.length()).map { index ->
            val item = subjects.getJSONObject(index)
            DoubanItem(item.getString("title"), item.optString("cover", ""), item.optString("rate", "").takeUnless { it == "0" }.orEmpty())
        }
    }

    internal fun hotList(ranking: HomeRanking): List<DoubanItem> {
        rankings[ranking]?.let { (fetchedAt, items) ->
            if (System.nanoTime() - fetchedAt < TimeUnit.HOURS.toNanos(1)) return items
        }
        var failure: Exception? = null
        for (base in rankingBases) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                val bytes = directBytes(base + ranking.path, accept = "application/json", timeoutMs = 5_000)
                val response = JSONObject(bytes.toString(Charsets.UTF_8))
                if (response.optInt("code") != 200) throw IOException("榜单服务暂不可用")
                val data = response.getJSONArray("data")
                val items = (0 until data.length()).mapNotNull { index ->
                    val item = data.optJSONObject(index) ?: return@mapNotNull null
                    val title = (item.opt("title") as? String).orEmpty().trim()
                    val cover = (item.opt("cover") as? String).orEmpty()
                        .ifBlank { (item.opt("cover_proxy") as? String).orEmpty() }
                    if (title.isBlank() || cover.isBlank()) return@mapNotNull null
                    val rating = item.optString("rating").takeIf {
                        ranking != HomeRanking.TV_POPULAR && (it.toDoubleOrNull() ?: 0.0) > 0.0
                    }.orEmpty()
                    DoubanItem(title, cover, rating)
                }
                if (items.isEmpty()) throw IOException("榜单暂无数据")
                rankings[ranking] = System.nanoTime() to items
                return items
            } catch (error: IOException) {
                failure = error
            } catch (error: JSONException) {
                failure = error
            }
        }
        throw IOException("榜单暂时无法加载", failure)
    }
    fun liveChannels(m3uUrl: String): List<Channel> = error("暂不支持直播列表解析")

    fun fetchText(url: String): String = directBytes(url).toString(Charsets.UTF_8)
    fun importSourceList(subscriptionUrl: String): List<SourceConfig> = parseSourceList(fetchText(subscriptionUrl))
    fun imageUrl(picUrl: String): String = picUrl
    fun imageBytes(picUrl: String): ByteArray? = try { directBytes(picUrl) } catch (_: java.io.IOException) { null }

    private fun directBytes(address: String, referer: String? = null, accept: String = "*/*", timeoutMs: Long = 15_000): ByteArray {
        val url = URL(address)
        val host = url.host.lowercase()
        val imageReferer = if (host == "douban.com" || host.endsWith(".douban.com") || host == "doubanio.com" || host.endsWith(".doubanio.com")) "https://movie.douban.com/" else null
        val conn = directConnection(url, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs), AppleCmsClient.USER_AGENT, accept, referer ?: imageReferer)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code, "")
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    /** 本地解析。兼容三种格式：网页版导出（LibreTV-Settings）、LibreTV-SourceList、TVBOX 配置。 */
    fun parseSourceList(text: String): List<SourceConfig> {
        exportSources(text)?.let { return it }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            try {
                JSONObject().put("sources", JSONArray(text))
            } catch (e2: Exception) {
                return emptyList()
            }
        }
        val arr = root.optJSONArray("sources") ?: root.optJSONArray("sites") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // TVBOX 的 sites 用 api 字段，且可能是 Spider（跳过）
            if (o.has("api") && o.optString("type", "") !in listOf("1", "0", "")) return@mapNotNull null
            if (o.has("api")) {
                SourceConfig.fromJson(
                    JSONObject().put("name", o.optString("name", o.optString("key", "")))
                        .put("url", o.optString("api", ""))
                        .put("isAdult", false),
                )
            } else {
                SourceConfig.fromJson(o)
            }
        }
    }

    /**
     * 网页版「设置 → 导出」生成的文件（LibreTV-Settings_*.json）：
     *   { data: { "libretv-settings": "<JSON 字符串>", viewingHistory: "<JSON 字符串>" } }
     * 内层是 zustand 的持久化结构，真正的片源在 state.customAPIs，
     * 启用与顺序在 state.selectedKeys（值对应 customAPIs 的 key，如 custom_1）。
     */
    private fun exportState(text: String): JSONObject? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        val inner = data.optString("libretv-settings", "")
        if (inner.isEmpty()) return null
        return try {
            JSONObject(inner).optJSONObject("state") ?: return null
        } catch (e: Exception) {
            null
        }
    }

    private fun exportSources(text: String): List<SourceConfig>? {
        val state = exportState(text) ?: return null
        val apis = state.optJSONArray("customAPIs") ?: return emptyList()
        val byKey = LinkedHashMap<String, SourceConfig>()
        for (i in 0 until apis.length()) {
            val o = apis.optJSONObject(i) ?: continue
            val sc = SourceConfig.fromJson(o) ?: continue
            byKey[o.optString("key", sc.url)] = sc
        }
        val selected = state.optJSONArray("selectedKeys")
        if (selected == null || selected.length() == 0) return byKey.values.toList()
        val ordered = (0 until selected.length()).mapNotNull { byKey[selected.optString(it, "")] }
        // selectedKeys 里一个都对不上时，退回全部（宁多勿少，用户可在 App 里删）
        return if (ordered.isEmpty()) byKey.values.toList() else ordered
    }

    /** 同一份导出文件里的直播源（M3U 订阅地址）。 */
    fun parseLiveFromExport(text: String): List<String> {
        val state = exportState(text) ?: return emptyList()
        val out = LinkedHashSet<String>()
        val subs = state.optJSONArray("liveSubscriptions")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val o = subs.optJSONObject(i)
                val u = if (o != null) o.optString("url", "") else subs.optString(i, "")
                if (u.isNotEmpty()) out.add(u)
            }
        }
        val sel = state.optJSONArray("liveSelectedUrls")
        if (sel != null) {
            for (i in 0 until sel.length()) {
                val u = sel.optString(i, "")
                if (u.isNotEmpty()) out.add(u)
            }
        }
        return out.toList()
    }


}
