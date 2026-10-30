package com.dpadtv.client

import org.json.JSONArray
import org.json.JSONObject

data class BackupSettings(
    val seekStepSeconds: Int,
    val autoNext: Boolean,
    val resumeEnabled: Boolean,
    val resumeThresholdSeconds: Int,
    val playbackSpeed: Float,
    val decodeMode: Int,
    val cacheLimitMb: Int,
    val nextEpisodePreloadEnabled: Boolean,
    val homeBarOrder: List<String>,
    val homeBarHidden: Set<String>,
    val searchHistory: List<String>,
)

data class BackupSnapshot(
    val sources: List<SourceConfig>,
    val history: List<HistoryEntry>,
    val positions: Map<String, Long>,
    val settings: BackupSettings,
)

/** Native app format; deliberately separate from the web app's incompatible history schema. */
object AppBackup {
    fun encode(snapshot: BackupSnapshot): String = JSONObject().apply {
        put("format", "LibreTV-Android-TV-Backup")
        put("version", 1)
        put("createdAt", System.currentTimeMillis())
        put("sources", JSONArray().apply { snapshot.sources.forEach { put(it.toJson()) } })
        put("history", JSONArray().apply {
            snapshot.history.forEach { h ->
                put(JSONObject().put("title", h.title).put("playUrl", h.playUrl)
                    .put("positionMs", h.positionMs).put("durationMs", h.durationMs)
                    .put("updatedAt", h.updatedAt).put("cover", h.cover))
            }
        })
        put("positions", JSONObject(snapshot.positions))
        val s = snapshot.settings
        put("settings", JSONObject().put("seekStepSeconds", s.seekStepSeconds).put("autoNext", s.autoNext)
            .put("resumeEnabled", s.resumeEnabled).put("resumeThresholdSeconds", s.resumeThresholdSeconds)
            .put("playbackSpeed", s.playbackSpeed).put("decodeMode", s.decodeMode).put("cacheLimitMb", s.cacheLimitMb)
            .put("nextEpisodePreloadEnabled", s.nextEpisodePreloadEnabled).put("homeBarOrder", JSONArray(s.homeBarOrder))
            .put("homeBarHidden", JSONArray(s.homeBarHidden.toList())).put("searchHistory", JSONArray(s.searchHistory)))
    }.toString(2)

    /** Validate once at the file boundary; no preferences are written while parsing. */
    fun decode(text: String): BackupSnapshot {
        val root = JSONObject(text)
        require(root.getString("format") == "LibreTV-Android-TV-Backup") { "这不是 LibreTV 投影仪版备份" }
        require(root.getInt("version") == 1) { "暂不支持这个备份版本" }
        val sources = root.getJSONArray("sources").let { rows ->
            List(rows.length()) { index ->
                requireNotNull(SourceConfig.fromJson(rows.getJSONObject(index))) { "片源地址缺失" }
            }
        }
        val history = root.getJSONArray("history").let { rows ->
            List(rows.length()) { index ->
                val h = rows.getJSONObject(index)
                HistoryEntry(h.getString("title"), h.getString("playUrl"), h.getLong("positionMs"),
                    h.getLong("durationMs"), h.getLong("updatedAt"), h.getString("cover")).also {
                    require(it.playUrl.isNotEmpty() && it.positionMs >= 0 && it.durationMs >= 0) { "播放历史无效" }
                }
            }
        }
        val positions = root.getJSONObject("positions").let { rows ->
            rows.keys().asSequence().associateWith { key ->
                require(key.matches(Regex("pos:-?\\d+"))) { "续播记录无效" }
                rows.getLong(key).also { require(it >= 0) { "续播位置无效" } }
            }
        }
        val s = root.getJSONObject("settings")
        val settings = BackupSettings(s.getInt("seekStepSeconds"), s.getBoolean("autoNext"), s.getBoolean("resumeEnabled"),
            s.getInt("resumeThresholdSeconds"), s.getDouble("playbackSpeed").toFloat(), s.getInt("decodeMode"),
            s.getInt("cacheLimitMb"), s.getBoolean("nextEpisodePreloadEnabled"),
            s.getJSONArray("homeBarOrder").let { rows -> List(rows.length()) { rows.getString(it) } },
            s.getJSONArray("homeBarHidden").let { rows -> List(rows.length()) { rows.getString(it) }.toSet() },
            s.getJSONArray("searchHistory").let { rows -> List(rows.length()) { rows.getString(it) } })
        require(settings.seekStepSeconds > 0 && settings.resumeThresholdSeconds >= 0 &&
            settings.playbackSpeed.isFinite() && settings.playbackSpeed in 0.25f..4f &&
            settings.decodeMode in 0..2 && settings.cacheLimitMb in 1..4096) { "备份中的播放设置无效" }
        return BackupSnapshot(sources, history, positions, settings)
    }
}
