package com.dpadtv.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppBackupTest {
    private val snapshot = BackupSnapshot(
        sources = listOf(SourceConfig("启用源", "https://a.example/api", "https://a.example/detail"),
            SourceConfig("停用源", "https://b.example/api", enabled = false)),
        history = listOf(HistoryEntry("三体 · 第 2/30 集", "https://video.example/2.m3u8", 123_456, 2_400_000, 42, "https://image.example/cover.jpg")),
        positions = mapOf("pos:-12345" to 123_456L, "pos:88" to 30_000L),
        settings = BackupSettings(30, false, false, 60, 1.5f, 2, 512, false,
            HomeBar.DEFAULT.reversed(), setOf("show"), listOf("三体", "流浪地球")),
    )

    @Test fun restoresSourcesHistoryProgressAndSettingsExactly() {
        val restored = AppBackup.decode(AppBackup.encode(snapshot))
        assertEquals(snapshot, restored)
        assertFalse(restored.sources[1].enabled)
        assertEquals(123_456L, restored.history.single().positionMs)
    }

    @Test fun emptyListsRemainEmptyInsteadOfSeedingDefaultSources() {
        val empty = snapshot.copy(sources = emptyList(), history = emptyList(), positions = emptyMap())
        assertEquals(empty, AppBackup.decode(AppBackup.encode(empty)))
    }

    @Test fun rejectsOtherApplicationsFilesAndFutureVersions() {
        val json = JSONObject(AppBackup.encode(snapshot))
        json.put("format", "LibreTV-Settings")
        assertThrows(IllegalArgumentException::class.java) { AppBackup.decode(json.toString()) }
        json.put("format", "LibreTV-Android-TV-Backup").put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { AppBackup.decode(json.toString()) }
    }

    @Test fun rejectsIncompleteHistoryRatherThanSilentlyDiscardingIt() {
        val json = JSONObject(AppBackup.encode(snapshot))
        json.getJSONArray("history").getJSONObject(0).remove("playUrl")
        assertThrows(org.json.JSONException::class.java) { AppBackup.decode(json.toString()) }
    }

    @Test fun rejectsInvalidCacheLimitBeforeAnythingIsApplied() {
        val json = JSONObject(AppBackup.encode(snapshot))
        json.getJSONObject("settings").put("cacheLimitMb", -1)
        assertThrows(IllegalArgumentException::class.java) { AppBackup.decode(json.toString()) }
    }

    @Test fun rejectsBrokenSourceInsteadOfRestoringAPartialList() {
        val json = JSONObject(AppBackup.encode(snapshot))
        json.getJSONArray("sources").put(JSONObject().put("url", ""))
        assertThrows(IllegalArgumentException::class.java) { AppBackup.decode(json.toString()) }
    }

    @Test fun rejectsCorruptJson() {
        assertThrows(org.json.JSONException::class.java) { AppBackup.decode("not a backup") }
    }
}
