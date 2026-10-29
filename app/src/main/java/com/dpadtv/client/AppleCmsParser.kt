package com.dpadtv.client

import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

data class CmsPage(val items: List<SearchItem>, val pageCount: Int)

/** Kotlin adaptation of LibreTV v2.21.1 cms-parser.ts. SPDX-License-Identifier: AGPL-3.0-or-later */
object AppleCmsParser {
    private val m3u8 = Regex("\\\$https?://[^\"'\\s]+?\\.m3u8")
    private val punctuation = Regex("[\\s\\p{Z}\\p{P}\\p{S}]+")
    private val adultTypes = listOf("伦理片", "福利", "倫理片", "福利片", "日本伦理", "日本福利", "日本福利片", "日本伦理片", "擦边短剧", "擦边", "韩国伦理", "里番动漫", "门事件", "萝莉少女", "制服诱惑", "国产传媒", "cosplay", "黑丝诱惑", "无码", "日本无码", "有码", "日本有码", "SWAG", "网红主播", "色情片", "同性片", "福利视频", "无码高清", "有码高清", "91视频", "自拍偷拍", "mini传媒")

    fun episodes(playUrl: String): List<String> {
        val groups = playUrl.split("\$\$\$").map { group ->
            group.split('#').mapNotNull { episode ->
                episode.split('$').getOrNull(1)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        }.filter { it.isNotEmpty() }
        return groups.firstOrNull { group -> group.any { ".m3u8" in it } } ?: groups.firstOrNull().orEmpty()
    }

    fun searchPage(data: JSONObject, source: SourceConfig): CmsPage {
        val rawList = data.opt("list")
        require(rawList == null || rawList === JSONObject.NULL || rawList is JSONArray) { "API返回的数据格式无效" }
        val list = rawList as? JSONArray ?: JSONArray()
        val items = (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            val id = row.opt("vod_id").takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
            SearchItem(source.name, id, row.string("vod_name"), row.string("vod_pic"), row.string("type_name"), row.year(), row.string("vod_remarks"), source.url)
        }
        val pageCount = data.opt("pagecount")?.toString()?.toDoubleOrNull()?.toInt() ?: 1
        return CmsPage(items, pageCount.coerceIn(1, 5))
    }

    /** LibreTV v2.21.1 video-card.tsx aggregateResults：精确片名合并，冲突年份拆开。 */
    fun aggregate(items: List<SearchItem>): List<SearchGroup> =
        items.groupBy { it.name.trim() }.filterKeys { it.isNotEmpty() }.flatMap { (name, matches) ->
            val years = matches.map { it.year }.filter { it.isNotEmpty() }.distinct()
            if (years.size <= 1) listOf(SearchGroup(name, years.firstOrNull().orEmpty(), matches))
            else {
                val dated = years.map { year -> SearchGroup(name, year, matches.filter { it.year == year }) }
                val unknown = matches.filter { it.year.isEmpty() }
                dated + if (unknown.isEmpty()) emptyList() else listOf(SearchGroup(name, "", unknown))
            }
        }

    fun detail(data: JSONObject): Detail {
        val list = data.optJSONArray("list")
        require(list != null && list.length() > 0) { "获取到的详情内容无效" }
        val row = list.getJSONObject(0)
        val playUrl = row.opt("vod_play_url").takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
        val urls = episodes(playUrl).ifEmpty { m3u8.findAll(row.string("vod_content")).map { it.value.substring(1) }.toList() }
        return Detail(urls, VideoInfo(
            title = row.string("vod_name").trim(), cover = row.string("vod_pic").trim(),
            desc = row.string("vod_content").trim(), remarks = row.string("vod_remarks").trim(),
            year = row.year(), area = row.string("vod_area").trim(), actor = row.string("vod_actor").trim(),
        ))
    }

    fun normalize(title: String): String = title.lowercase(Locale.ROOT).replace(punctuation, "")
    fun isAdult(item: SearchItem): Boolean = adultTypes.any { it in item.typeName }

    fun relevant(items: List<SearchItem>, keyword: String): List<SearchItem> {
        val normalized = normalize(keyword)
        if (normalized.isEmpty()) return items
        val querySeason = season(keyword, annotations = false, bareNeedsSpace = false)
        val tokens = keyword.trim().split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }
        val baseQuery = querySeason?.let { normalize(it.base) }
        val baseTokens = querySeason?.base?.trim()?.split(Regex("\\s+"))?.map(::normalize).orEmpty()
        return items.filter { item ->
            val name = normalize(item.name)
            if (name.isEmpty()) return@filter false
            val titleSeason = season(item.name, annotations = true, bareNeedsSpace = true)
            if (querySeason != null && titleSeason != null) {
                if (querySeason.number != titleSeason.number) return@filter false
                if (querySeason.explicit || titleSeason.explicit) {
                    val titleBase = normalize(titleSeason.base)
                    return@filter includesKeyword(titleBase, baseQuery!!) || (baseTokens.size > 1 && baseTokens.all { includesKeyword(titleBase, it) })
                }
            }
            includesKeyword(name, normalized) || (tokens.size > 1 && tokens.all { includesKeyword(name, it) })
        }
    }

    private data class Season(val base: String, val number: Int, val explicit: Boolean)

    private fun season(title: String, annotations: Boolean, bareNeedsSpace: Boolean): Season? {
        val explicitPattern = if (annotations) "^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季.*$" else "^(.*?)\\s*第\\s*([1-9]\\d?|[一二两三四五六七八九十]{1,3})\\s*季$"
        val explicit = Regex(explicitPattern).matchEntire(title.trim())
        val barePattern = if (bareNeedsSpace) "^(.*?)\\s+([1-9]\\d?)$" else "^(.*?)\\s*([1-9]\\d?)$"
        val match = explicit ?: Regex(barePattern).matchEntire(title.trim()) ?: return null
        val base = match.groupValues[1]
        if (normalize(base).isEmpty()) return null
        val value = match.groupValues[2].replace('两', '二')
        val digits = "零一二三四五六七八九"
        val number = value.toIntOrNull() ?: when {
            value.length == 1 && digits.indexOf(value[0]) > 0 -> digits.indexOf(value[0])
            Regex("^[一二三四五六七八九]?十[一二三四五六七八九]?$").matches(value) -> {
                val parts = value.split('十')
                (parts[0].firstOrNull()?.let(digits::indexOf) ?: 1) * 10 + (parts[1].firstOrNull()?.let(digits::indexOf) ?: 0)
            }
            else -> null
        } ?: return null
        return Season(base, number, explicit != null)
    }

    private fun includesKeyword(title: String, keyword: String): Boolean {
        if (title.isEmpty() || keyword.isEmpty()) return false
        var index = title.indexOf(keyword)
        while (index >= 0) {
            val before = title.getOrNull(index - 1)
            val after = title.getOrNull(index + keyword.length)
            if ((keyword.first() !in '0'..'9' || before == null || before !in '0'..'9') &&
                (keyword.last() !in '0'..'9' || after == null || after !in '0'..'9')) return true
            index = title.indexOf(keyword, index + 1)
        }
        return false
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String ?: ""

    private fun JSONObject.year(): String = when (val value = opt("vod_year")) {
        is String -> value.trim()
        is Number -> value.toString()
        else -> ""
    }
}
