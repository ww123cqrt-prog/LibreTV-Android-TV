package com.dpadtv.client

import org.junit.Assert.*
import org.junit.Test

class SearchAggregationTest {
    private fun item(name: String, year: String = "", source: String = "a") =
        SearchItem(source, name + year, name, "", "", year, "", "https://$source.example/api")

    @Test fun combinesSourcesAndTrimsTitles() {
        val a = item(" 三体 ", "2023")
        val b = item("三体", "2023", "b")
        val groups = AppleCmsParser.aggregate(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals("三体", groups.single().name)
        assertEquals(listOf(a, b), groups.single().items)
    }

    @Test fun separatesRemakesAndKeepsUnknownYearSeparateWhenAmbiguous() {
        val groups = AppleCmsParser.aggregate(listOf(item("沙丘", "1984"), item("沙丘", "2021", "b"), item("沙丘", source = "c")))
        assertEquals(listOf("1984", "2021", ""), groups.map { it.year })
        assertTrue(groups.all { it.items.size == 1 })
    }

    @Test fun joinsUnknownYearWhenOnlyOneKnownYearExists() {
        val a = item("三体")
        val b = item("三体", "2023", "b")
        val group = AppleCmsParser.aggregate(listOf(a, b)).single()
        assertEquals("2023", group.year)
        assertEquals(listOf(a, b), group.items)
    }

    @Test fun doesNotMergeDifferentSeasonsOrEditions() {
        val groups = AppleCmsParser.aggregate(listOf(item("三体"), item("三体 动画版"), item("三体 第二季")))
        assertEquals(3, groups.size)
    }

    @Test fun lateSourcesDoNotReorderExistingTitles() {
        val a = item("三体", "2023")
        val b = item("沙丘", "2021")
        val groups = AppleCmsParser.aggregate(listOf(a, b, item("三体", "2023", "c")))
        assertEquals(listOf("三体", "沙丘"), groups.map { it.name })
        assertEquals(2, groups.first().items.size)
        assertEquals(a, groups.first().items.first())
    }

    @Test fun discardsBlankTitlesAndPreservesExactUpstreamNameMatching() {
        val groups = AppleCmsParser.aggregate(listOf(item(" "), item("ABC"), item("abc"), item("A.B.C")))
        assertEquals(listOf("ABC", "abc", "A.B.C"), groups.map { it.name })
    }
}
