# Native CMS P0 Implementation Plan

> Execute inline with superpowers:executing-plans. User-selected architecture and scope: ../specs/2026-10-28-native-cms-p0.md.

**Goal:** Validate Apple CMS search, JSON details and direct playback in a side-by-side Android TV APK.

**Architecture:** Preserve DpadTV UI/player. Add a small Kotlin parser/client, delegate its search/details calls, and bypass backend setup for this P0 variant. No local server or embedded Node.js.

**Tech stack:** Existing AGP 9.3.0, Gradle 9.8.2 on this machine, SDK 37, Java 21, Kotlin, HttpURLConnection, existing Media3.

- [x] Task 1 — tests and provenance: create app/src/test/java/com/dpadtv/client/AppleCmsParserTest.kt and v2.21.1 oracle fixtures, add JUnit and JVM org.json dependencies. Add only compiling parser signatures returning empty values. Run :app:testDebugUnitTest and confirm non-empty fixture assertions fail.
- [x] Task 2 — parser: implement AppleCmsParser.kt from the pinned LibreTV functions. Run :app:testDebugUnitTest; all fixture assertions must pass, including unrelated search results, season aliases, null lists and multiple playback lines.
- [x] Task 3 — HTTP client: add AppleCmsClient.kt and AppleCmsClientTest.kt using a local JDK HTTP server. Verify direct encoded queries, up to five pages, detail URLs and partial source failure; then implement the client and rerun tests.
- [x] Task 4 — isolated APK: update applicationId, label, private signing setup, Api.kt, MainActivity.kt and initial-source flag in Prefs.kt. Seed only user-provided sources. Replace server startup/settings with local search and source management; keep source imports/player/history. Disable upstream APK update check in this variant. Build :app:assembleDebug and :app:assembleDebugAndroidTest.
- [x] Task 5 — device proof: add NativeCmsInstrumentation.kt under androidTest to run the production client on the N5, capturing per-source outcomes, detail episode counts and timing. Install only the independent package and test package. Verify original package metadata before/after; exercise native UI and playback, collect resource snapshots.
- [x] Task 6 — review and delivery: review the whole diff, rerun relevant checks after fixes, write coverage/acceptance report, package APK and corresponding source into this chat's outputs. Keep physical Windows-off acceptance explicitly pending if not performed.

Historical P0 commands used Gradle 9.8.2 and a local device connection helper. Current public build commands use the repository wrapper; see README.md. Remote publication was outside this historical P0 stage.
