# Restore native recommendation home

Scope correction requested by the user on 2026-10-28: the standalone app must retain DpadTV's recommendation home, not stop at a search-only prototype. This is within the previously requested core UI/function coverage; the user selected Kotlin-first and retaining the original UI, so no new architecture approval is needed.

Design: reuse DpadTV's existing movie/TV/variety recommendation grid and search-title click action. Replace Api.douban with an Android direct GET to the pinned LibreTV v2.21.1 Douban upstream endpoint. Map variety to type=tv/tag=综艺. Send the same public User-Agent and Referer headers, including for Douban cover hosts. No embedded server, public proxy, account login or new UI framework. Retain errors and a working search exit when the upstream is unavailable.

Verification: JVM transport/metadata/header tests first; device test mode for each of the three categories and actual decoded cover images; original recommendation UI on N5 plus recommendation-to-CMS search/detail playback; update same package with retained certificate and verify original DpadTV prefs unchanged. Do not claim physical Windows shutdown or full LibreTV completion.
