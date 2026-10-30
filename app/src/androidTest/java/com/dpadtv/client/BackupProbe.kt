package com.dpadtv.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Restoring fixtures are emulator-only; the projector case only exports its current data. */
class BackupProbe(private val instrumentation: Instrumentation) {
    fun downloads(): JSONObject {
        check(Build.VERSION.SDK_INT >= 29)
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun backups(): Set<Long> = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf("Download/LibreTV/", "LibreTV-backup-%.json"), null)!!.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val oldIds = backups()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("showSetup").apply { isAccessible = true }.invoke(activity)
            }
            val preferences = context.getSharedPreferences("dpadtv_native", 0)
            val before = preferences.all.toMap()
            val expected = AppBackup.decode(Prefs(context).exportBackup())
            val export = MainActivity::class.java.getDeclaredMethod("exportBackupFile").apply { isAccessible = true }
            instrumentation.runOnMainSync { export.invoke(activity) }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var added = emptySet<Long>()
            while (System.nanoTime() < end) {
                added = backups() - oldIds
                if (added.isNotEmpty()) break
                Thread.sleep(25)
            }
            check(added.size == 1) { "One-click export did not publish exactly one backup" }
            val uri = ContentUris.withAppendedId(collection, added.single())
            val restored = resolver.openInputStream(uri)!!.bufferedReader().use { AppBackup.decode(it.readText()) }
            check(restored == expected && preferences.all == before) { "Downloads export changed or lost data" }
            return JSONObject().put("case", "backup-downloads").put("path", "Download/LibreTV")
                .put("sources", expected.sources.size).put("history", expected.history.size).put("preferencesUnchanged", true)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            (backups() - oldIds).forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
        }
    }

    fun run(caseId: String): JSONObject {
        val context = instrumentation.targetContext
        val prefs = Prefs(context)
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        if (caseId == "backup-export") {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val file = File(context.cacheDir, "backup-export-probe.json")
            try {
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("showSetup").apply { isAccessible = true }.invoke(activity)
                }
                val before = sp.all.toMap()
                val expected = AppBackup.decode(prefs.exportBackup())
                file.delete()
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                        .invoke(activity, 1003, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                }
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < end && !file.exists()) Thread.sleep(25)
                instrumentation.waitForIdleSync()
                // The writer closes on the I/O dispatcher before the completion toast.
                var decoded: BackupSnapshot? = null
                while (System.nanoTime() < end) {
                    decoded = runCatching { AppBackup.decode(file.readText()) }.getOrNull()
                    if (decoded != null) break
                    Thread.sleep(25)
                }
                check(decoded == expected) { "File export did not preserve the current configuration" }
                check(sp.all == before) { "File export changed preferences" }
                return JSONObject().put("case", caseId).put("sources", expected.sources.size)
                    .put("history", expected.history.size).put("preferencesUnchanged", true)
                    .put("bytes", file.length())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                file.delete()
            }
        }

        require(caseId == "backup-roundtrip" && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Restore fixture requires a disposable emulator"
        }
        val before = sp.all.toMap()
        var restoreActivity: MainActivity? = null
        val restoreFile = File(context.cacheDir, "backup-restore-probe.json")
        try {
            prefs.saveSources(listOf(SourceConfig("One", "https://one.example/api"),
                SourceConfig("Disabled", "https://disabled.example/api", enabled = false)))
            prefs.clearHistory()
            prefs.clearAllProgress()
            prefs.upsertHistory(HistoryEntry("备份验收 · 第 2/3 集", "https://video.example/2.m3u8", 25_000, 120_000, 42, "https://image.example/a.jpg"))
            prefs.saveProgress("https://video.example/2.m3u8", 25_000)
            prefs.saveProgress("https://video.example/other.m3u8", 50_000)
            prefs.seekStepSeconds = 30
            prefs.autoNext = false
            prefs.resumeEnabled = false
            prefs.playbackSpeed = 1.5f
            prefs.nextEpisodePreloadEnabled = false
            prefs.saveHomeBarOrder(HomeBar.DEFAULT.reversed())
            prefs.saveHomeBarHidden(setOf("show"))
            prefs.clearSearchHistory()
            prefs.addSearchHistory("备份验收")
            sp.edit().putString("password", "fixture-only-password").putString("cookie", "fixture-only-cookie").commit()
            val text = prefs.exportBackup()
            check(!text.contains("fixture-only-password") && !text.contains("fixture-only-cookie"))
            val expected = AppBackup.decode(text)
            prefs.saveSources(emptyList())
            prefs.clearHistory()
            prefs.clearAllProgress()
            prefs.autoNext = true
            prefs.nextEpisodePreloadEnabled = true
            check(prefs.restoreBackup(expected))
            check(AppBackup.decode(prefs.exportBackup()) == expected) { "Restored preferences differ from the backup" }
            check(!prefs.sources()[1].enabled && prefs.progressOf("https://video.example/other.m3u8") == 50_000L)
            check(prefs.nativeSourcesInitialized)
            val priorFailure = sp.all.toMap()
            val broken = JSONObject(text).apply { getJSONObject("settings").put("cacheLimitMb", -1) }.toString()
            check(runCatching { AppBackup.decode(broken) }.isFailure)
            check(sp.all == priorFailure) { "Invalid backup changed preferences" }

            restoreFile.writeText(text)
            restoreActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val target = restoreActivity
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("showSetup").apply { isAccessible = true }.invoke(target)
            }
            fun fileResult(resultCode: Int) = instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                    .invoke(target, 1004, resultCode, Intent().setData(Uri.fromFile(restoreFile)))
            }
            fun dialogButton(title: String, label: String) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                while (System.nanoTime() < deadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val button = root?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label && it.isClickable }
                    if (root?.findAccessibilityNodeInfosByText(title)?.isNotEmpty() == true &&
                        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        instrumentation.waitForIdleSync()
                        return
                    }
                    Thread.sleep(25)
                }
                error("Backup dialog did not show: $title / $label")
            }
            prefs.saveSources(emptyList())
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("showSetup").apply { isAccessible = true }.invoke(target)
            }
            val beforeCancel = sp.all.toMap()
            fileResult(Activity.RESULT_CANCELED)
            check(sp.all == beforeCancel) { "Cancelling the picker changed preferences" }
            fileResult(Activity.RESULT_OK)
            dialogButton("恢复备份", "取消")
            check(sp.all == beforeCancel) { "Cancelling restore confirmation changed preferences" }
            fileResult(Activity.RESULT_OK)
            dialogButton("恢复备份", "恢复")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var restoredUi = false
            while (System.nanoTime() < deadline) {
                restoredUi = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("本机直连 · ${expected.sources.size} 个片源")?.isNotEmpty() == true
                if (restoredUi && AppBackup.decode(prefs.exportBackup()) == expected) break
                Thread.sleep(25)
            }
            check(restoredUi && AppBackup.decode(prefs.exportBackup()) == expected) {
                "Confirmed file restore did not finish updating data and settings UI"
            }
            val beforeInvalid = sp.all.toMap()
            restoreFile.writeText("invalid backup")
            fileResult(Activity.RESULT_OK)
            dialogButton("备份操作失败", "确定")
            check(sp.all == beforeInvalid) { "Invalid file callback changed preferences" }
            val empty = expected.copy(sources = emptyList(), history = emptyList(), positions = emptyMap())
            check(prefs.restoreBackup(empty))
            check(prefs.sources().isEmpty() && prefs.history().isEmpty() && prefs.progressCount() == 0)
            check(prefs.nativeSourcesInitialized)
            return JSONObject().put("case", caseId).put("roundTrip", true).put("disabledSourcePreserved", true)
                .put("sparseProgressPreserved", true).put("invalidFileKeptData", true).put("emptyRestore", true)
                .put("fileConfirmationAndCancel", true).put("invalidFileThroughUiKeptData", true)
        } finally {
            instrumentation.runOnMainSync { restoreActivity?.finish() }
            instrumentation.waitForIdleSync()
            restoreFile.delete()
            val editor = sp.edit().clear()
            before.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                }
            }
            check(editor.commit() && sp.all == before) { "Backup fixture did not restore emulator preferences" }
        }
    }
}
