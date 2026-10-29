package com.dpadtv.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NumericYearTest {
    private val source = SourceConfig("test", "https://cms.example/api")

    @Test fun numericRemakeYearsStaySeparateAndSwitchOnlyToTheCurrentYear() {
        val page = AppleCmsParser.searchPage(JSONObject("""{"list":[
            {"vod_id":1,"vod_name":"沙丘","vod_year":1984},
            {"vod_id":2,"vod_name":"沙丘","vod_year":2021},
            {"vod_id":3,"vod_name":"沙丘","vod_year":" 2021 "}
        ]}"""), source)
        assertEquals(listOf("1984", "2021", "2021"), page.items.map { it.year })
        assertEquals(2, AppleCmsParser.aggregate(page.items).size)
        assertEquals(listOf("2", "3"), PlaybackSourceRules.matching(page.items, "沙丘", "2021").map { it.vodId })
    }

    @Test fun detailPreservesNumericYear() {
        val detail = AppleCmsParser.detail(JSONObject("""{"list":[{
            "vod_name":"沙丘","vod_year":2021,"vod_play_url":"正片${'$'}https://media.example/a.m3u8"
        }]}"""))
        assertEquals("2021", detail.info.year)
    }

    @Test fun missingOrWrongYearTypesRemainUnknown() {
        val page = AppleCmsParser.searchPage(JSONObject("""{"list":[
            {"vod_id":1,"vod_name":"测试","vod_year":null},
            {"vod_id":2,"vod_name":"测试","vod_year":true}
        ]}"""), source)
        assertTrue(page.items.all { it.year.isEmpty() })
    }
}
