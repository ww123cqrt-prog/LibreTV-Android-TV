package com.dpadtv.client

import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceRulesTest {
    private fun item(title: String, year: String = "", source: String = "a") =
        SearchItem(source, title + year, title, "", "", year, "", "https://$source.example/api")

    @Test fun readsLegacyEpisodeWithoutChangingTheSeriesName() {
        assertEquals(PlaybackIdentity("三体", 11), PlaybackSourceRules.historyIdentity("三体 · 第 12/30 集"))
    }
    @Test fun plainMovieAndSeasonTitlesStayIntact() {
        assertEquals(PlaybackIdentity("庆余年 第二季", 0), PlaybackSourceRules.historyIdentity("庆余年 第二季"))
    }
    @Test fun picksKnownYearInsteadOfARemake() {
        val original = item("沙丘", "2021")
        assertEquals(listOf(original), PlaybackSourceRules.matching(listOf(item("沙丘", "1984"), original), "沙丘", "2021"))
    }
    @Test fun doesNotMixCommentaryOrOtherSeasons() {
        val original = item("三体")
        assertEquals(listOf(original), PlaybackSourceRules.matching(listOf(original, item("三体[电影解说]"), item("三体 第二季")), "三体", ""))
    }
    @Test fun unknownYearsAreUsableOnlyWithoutConflictingKnownYear() {
        val unknown = item("沙丘")
        assertEquals(listOf(unknown), PlaybackSourceRules.matching(listOf(unknown), "沙丘", "2021"))
        assertTrue(PlaybackSourceRules.matching(listOf(unknown, item("沙丘", "1984")), "沙丘", "2021").isEmpty())
    }
    @Test fun legacyHistoryCanOfferClearlyLabelledYearVariants() {
        assertEquals(2, PlaybackSourceRules.matching(listOf(item("沙丘", "1984"), item("沙丘", "2021")), "沙丘", "").size)
    }
}
