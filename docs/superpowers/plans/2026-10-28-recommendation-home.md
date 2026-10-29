# Native recommendation home implementation plan

> Execute inline with superpowers:executing-plans, using the user's existing design and correction in ../specs/2026-10-28-recommendation-home.md.

**Goal:** Restore DpadTV movie/TV/variety poster home while all recommendation and cover requests originate on the projector.

**Architecture:** Existing Api and directConnection transport; original MainActivity recommendation grid. No new runtime/server or UI redesign.

**Tech stack:** Kotlin / HttpURLConnection / existing Media3 and Android Instrumentation, JVM JUnit local HTTP server.

- [x] Add failing tests to AppleCmsClientTest for direct recommendation GET, public Referer, query/pagination, variety normalization, rating and HTTP failure. Run to observe failure.
- [x] Implement Api.douban and public Douban-cover Referer on directConnection; no generic proxy.
- [x] Restore recommendation startup, search hot lists, navigation back to home, and local homepage toolbar settings. Keep source/server/update isolation guards where required; preserve real errors and search exit.
- [x] Add recommendation-only test-APK mode. Build tests/main/test APKs, install -r with retained signing key.
- [x] Probe movie/TV/variety and covers on N5; verify original UI, entry-to-search path, installed data preservation and unchanged original app.
- [x] Review diff, record measured results and missing checks, commit, deliver 0.1.1-p0 APK and matching source with checksums.
