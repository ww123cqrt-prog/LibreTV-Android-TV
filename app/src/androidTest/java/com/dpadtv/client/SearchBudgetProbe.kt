package com.dpadtv.client

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

/** Exercises the user-visible search including its cleaned-keyword retry. */
class SearchBudgetProbe(private val instrumentation: Instrumentation) {
    fun run(): JSONObject {
        val context = instrumentation.targetContext
        val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val sp = context.getSharedPreferences("dpadtv_native", 0)
        val savedSources = sp.getString("sources", null)
        val savedSearchHistory = sp.getString("search_history", null)
        val server = RegressionHttpServer { uri ->
            if (uri.path == "/slow") Thread.sleep(9_000)
            """{"pagecount":1,"list":[]}""".toByteArray()
        }
        try {
            Prefs(context).saveSources(listOf(
                SourceConfig("Budget empty", server.base + "/empty"),
                SourceConfig("Budget slow", server.base + "/slow"),
            ))
            val search = MainActivity::class.java.getDeclaredMethod("doSearch", String::class.java)
                .apply { isAccessible = true }
            val finishedField = MainActivity::class.java.getDeclaredField("searchFinished")
                .apply { isAccessible = true }
            val started = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync { search.invoke(main, "Budget(2026)") }
            var finished = false
            while (SystemClock.elapsedRealtime() - started < 13_500) {
                instrumentation.runOnMainSync { finished = finishedField.getBoolean(main) }
                if (finished) break
                Thread.sleep(50)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            check(server.count("/empty") > 0 && server.count("/slow") > 0) {
                "Budget fixture was not searched"
            }
            check(finished) {
                "Search exceeded the shared 12s budget: finished=$finished elapsedMs=$elapsed " +
                    "emptyRequests=${server.count("/empty")} slowRequests=${server.count("/slow")}"
            }
            return JSONObject().put("case", "r5").put("searchFinished", finished)
                .put("elapsedMs", elapsed).put("emptyRequests", server.count("/empty"))
                .put("slowRequests", server.count("/slow"))
        } finally {
            instrumentation.runOnMainSync { main.finish() }
            instrumentation.waitForIdleSync()
            sp.edit().putString("sources", savedSources).putString("search_history", savedSearchHistory).commit()
            server.close()
        }
    }
}
