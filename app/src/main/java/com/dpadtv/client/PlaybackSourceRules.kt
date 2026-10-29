package com.dpadtv.client

data class SourceCandidate(val item: SearchItem, val detail: Detail, val responseMs: Long)
data class PlaybackIdentity(val title: String, val episodeIndex: Int)

object PlaybackSourceRules {
    /** 旧历史只有播放地址和本 App 生成的集数标题，不改写已有历史。 */
    fun historyIdentity(label: String): PlaybackIdentity {
        val match = Regex("^(.*) · 第 (\\d+)/(\\d+) 集$").matchEntire(label)
        val episode = match?.groupValues?.get(2)?.toIntOrNull()
        return if (match != null && episode != null && episode > 0)
            PlaybackIdentity(match.groupValues[1], episode - 1)
        else PlaybackIdentity(label, 0)
    }

    /** 沿用合卡的精确片名、年份规则，避免把翻拍或解说当成同一影片。 */
    fun matching(items: List<SearchItem>, title: String, year: String): List<SearchItem> {
        val named = AppleCmsParser.aggregate(items).filter { it.name == title.trim() }
        if (year.isEmpty()) return named.flatMap { it.items }
        val dated = named.filter { it.year == year }
        if (dated.isNotEmpty()) return dated.flatMap { it.items }
        return if (named.all { it.year.isEmpty() }) named.flatMap { it.items } else emptyList()
    }
}
