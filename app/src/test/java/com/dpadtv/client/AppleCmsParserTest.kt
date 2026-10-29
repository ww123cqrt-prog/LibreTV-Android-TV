package com.dpadtv.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppleCmsParserOracleTest(private val case: JSONObject) {
    companion object {
        private val fixture = JSONObject(AppleCmsParserOracleTest::class.java.getResourceAsStream("/libretv-v2.21.1-oracle.json")!!.bufferedReader().readText())
        private val sourceJson = fixture.getJSONObject("source")
        private val source = SourceConfig(sourceJson.getString("name"), sourceJson.getString("url"))
        @JvmStatic @Parameterized.Parameters(name = "upstream case {index}")
        fun cases(): List<Array<Any>> = fixture.getJSONArray("cases").let { a -> (0 until a.length()).map { arrayOf(a.getJSONObject(it)) } }
    }

    @Test fun matchesPinnedLibreTv() {
        when (case.getString("kind")) {
            "episodes" -> assertEquals(case.getJSONArray("expected").strings(), AppleCmsParser.episodes(case.getString("input")))
            "relevance" -> {
                val items = case.getJSONArray("titles").strings().map { SearchItem(sourceName = "source", vodId = "1", name = it, pic = "", typeName = "", year = "", remarks = "", sourceUrl = source.url) }
                assertEquals(case.getJSONArray("expected").strings(), AppleCmsParser.relevant(items, case.getString("query")).map { it.name })
            }
            "search" -> {
                val actual = AppleCmsParser.searchPage(case.getJSONObject("input"), source).items
                val expected = case.getJSONArray("expected")
                assertEquals(expected.length(), actual.size)
                actual.forEachIndexed { i, item ->
                    val e = expected.getJSONObject(i)
                    assertEquals(e.optString("vodId", ""), item.vodId)
                    assertEquals(e.optString("name", ""), item.name)
                    assertEquals(e.optString("pic", ""), item.pic)
                    assertEquals(e.optString("year", ""), item.year)
                    assertEquals(e.optString("sourceUrl", ""), item.sourceUrl)
                }
            }
            "detail" -> {
                val actual = AppleCmsParser.detail(case.getJSONObject("input"))
                val expected = case.getJSONObject("expected")
                assertEquals(expected.getJSONArray("episodes").strings(), actual.episodes)
                val info = expected.getJSONObject("videoInfo")
                assertEquals(info.optString("title", ""), actual.info.title)
                assertEquals(info.optString("cover", ""), actual.info.cover)
                assertEquals(info.optString("desc", ""), actual.info.desc)
                assertEquals(info.optString("actor", ""), actual.info.actor)
            }
        }
    }
}

class AppleCmsBoundaryTest {
    private val source = SourceConfig("test", "https://cms.example/api")
    @Test fun rejectsWrongListShape() {
        assertThrows(IllegalArgumentException::class.java) { AppleCmsParser.searchPage(JSONObject("{\"list\":{}}"), source) }
    }
    @Test fun rejectsEmptyDetail() {
        assertThrows(IllegalArgumentException::class.java) { AppleCmsParser.detail(JSONObject("{\"list\":[]}")) }
    }
    @Test fun readsAndCapsPages() {
        assertEquals(5, AppleCmsParser.searchPage(JSONObject("{\"list\":[],\"pagecount\":99}"), source).pageCount)
    }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
