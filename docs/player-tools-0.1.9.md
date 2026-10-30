# LibreTV 0.1.9：画质、备份与下一集预缓存

2026-10-30，**0.1.9 / versionCode 10**。保持 LibreTV 显示名称、包名 `local.libretv.standalone.tv` 和原开发签名，原 DpadTV 不变。

## 使用

- **实际分辨率：** 暂停或打开播放操作栏时显示视频实际报告的宽×高；未取得有效尺寸时显示“未知”。换集、换源先清除旧值，首帧后显示新尺寸。信息显示不会提高片源画质。
- **导出备份：** 设置 → 导出备份。Android 10 以上一键保存到 `Download/LibreTV/LibreTV-backup-日期-时间.json`，文件管理器通常显示为“下载/LibreTV”；N5 已用方向键和确认键完成实际导出。更老 Android 保留系统文件选择器，尚未实测。
- **恢复备份：** 设置 → 恢复备份 → 选择 JSON → 核对片源与历史数量 → 确认恢复。读取、格式／版本校验失败或取消不会写配置；确认后替换对应数据。
- **下一集预缓存：** 默认开启，设置 → 播放设置 → 下一集预缓存可关闭。只准备紧邻下一集开头，当前播放缓冲优先。

备份包含片源顺序与停用状态、观看历史和封面地址、全部已保存续播断点、搜索历史、播放及首页设置；不包含视频缓存和废弃服务器密码／Cookie。使用本适配版的版本化格式；网页版设置仍走原片源导入入口，不将网页版历史格式当成本机备份。

若设备写盘失败，SharedPreferences 的内存配置可能已经应用，界面会明确区分“已应用”与“尚未保存”。未增加低频磁盘失败回滚框架，本轮未模拟存储耗尽。

## 实现范围

复用固定 **Media3 1.5.1**、原生播放器和 SimpleCache，无新依赖、额外解码播放器、服务器或后台服务。前台正在播放、本集至少缓冲 **15 秒**或已全部缓完、且不在加载时，才启动预取。

HLS 目标为开头约 **12 秒**，单次应用读取最多 **4 MiB**，清单等也计入预算。字节上限优先，末片可只缓存前缀，由正常播放继续读取。普通 MP4 只预取文件开头，不寻找尾部 moov。仅选一个视频变体，最终自适应选轨、独立音轨等仍可能需要联网。4 MiB 不是严格线速流量口径，HTTP 头和服务端不支持 Range 导致的跳过数据不在此口径中。

暂停、后台、切集、换源、退出或当前播放重新加载时取消；在途读取受 **3 秒**预取超时约束，不阻塞主线程。可选预取失败不影响当前播放、不循环重试。没有承诺所有片源零等待，也未测量真实长剧换集的耗时改善。

## 验证与边界

| 检查 | 结果 |
| --- | --- |
| JVM | **67/67**：原 60 项及新增 7 项备份往返、空列表、错误格式／版本／字段与损坏数据检查 |
| 构建 | 主／测试 APK 均通过；主 APK 不包含测试短片、夹具或测试入口 |
| API 34 TV 模拟器 | **12 个定向场景通过**：实际配置恢复、文件导出、下载目录导出、分辨率、预取自动触发、暂停取消、HLS 缓存命中、4 MiB 上限、worker 取消、跨集快进、后台保存与恢复、空首批恢复分页 |
| 恢复 UI | 模拟文件选择返回后，实际验证确认、取消和错误弹窗；夹具曾一次未等待设置页刷新，补齐同步条件后连续两次通过 |
| N5 Pro | K981CN、Android 14 / API 34；**9 个安全场景通过**：两种无损导出、分辨率、自动预取、暂停取消、HLS 缓存命中、4 MiB 上限、worker 取消、空首批恢复分页 |
| N5 遥控流程 | 设置中方向键＋确认一键导出，文件读回含 **9 个片源、6 条历史**；恢复选择器可打开并取消 |
| 升级与数据 | 从 0.1.8 同签名覆盖；安装、测试、真实导出和取消后，原偏好 XML 逐字节一致。设备安装 APK 的 SHA-256 与交付包一致 |
| 独立审查 | 备份、播放器及最终集成审查通过；没有扩大为下载系统或低概率防御框架 |
| 光源 | 原厂“关闭光源”完成；`jmgo.dlp.enable=0`，新鲜后台日志同时确认 `setDlpPower:0` 与 `set(78) value:1 ret:1`，未发送关机或待机命令 |

替换恢复只在可丢弃模拟器执行，N5 没有替换真实用户数据。遥控测试使用 ADB 方向／确认注入；实体遥控长按、长期连续播放、投影仪重启、真正关闭 Windows 后的整体验收、FPS 和断网恢复时间未在本轮重测。

包含上一轮 P2 修复：首批推荐为空后，恢复成功清除旧的“没有下一页”标记，见 [0.1.8 预加载后续修复](recommend-prefetch-0.1.8.md#后续修复首批为空后的分页恢复)。

## 复测与产物

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb -s $serial shell am instrument -w -r -e mode regression -e case player-quality local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
# 安全 case：backup-export / backup-downloads / player-quality / player-prefetch-auto /
# player-prefetch-pause / next-episode-cache / next-episode-limit / next-episode-cancel / prefetch-empty-resume
# backup-roundtrip 仅限可丢弃模拟器，会临时替换配置；不要在日常设备运行。
```

签名证书 SHA-256：`3edd8eca1250b5cbb67794c9544e2da5ad6e67490e03ab015454b0be5a15d1e6`。

APK SHA-256：`be76851062f4d3a3db52c9088b739069f8110252062cc12995fc3804214958ef`。

原配置、真实备份和原始日志留在本机，不公开发布。实现依据：[自有下载文件存储](https://developer.android.com/training/data-storage/shared/media)、[Media3 1.5.1 缓存](https://github.com/androidx/media/blob/1.5.1/libraries/datasource/src/main/java/androidx/media3/datasource/cache/CacheDataSource.java)、[HLS 结构](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/playlist/HlsMediaPlaylist.java)。

## English usage

Version **0.1.9 / code 10** keeps the LibreTV label, package and signing identity, with the original DpadTV untouched. Pause or open playback controls to see actual video dimensions; unknown sizes stay unknown, and transitions clear the old value until the next first frame.

Settings → Export backup saves to **Download/LibreTV** in one action on Android 10+. Older systems retain the document picker and are untested. Restore opens the picker, shows source/history counts and asks before replacement. File read/validation failures and cancellation do not write preferences. Sources including disabled state/order, history/cover URLs, sparse resume positions, search history and active playback/home settings are included; video cache and obsolete server credentials are excluded. The native versioned format is distinct from web history backups. Disk-write failure may already have applied memory values; the UI accurately distinguishes applied from persisted state, without a disk-full rollback framework.

## English implementation scope

The feature retains **Media3 1.5.1**, SimpleCache and one native player, with no new dependency/server/service. Warm-up runs only during foreground playback, when not loading and at least **15 seconds** or the entire current item is buffered. HLS targets roughly **12 seconds**, capped at **4 MiB** of application reads including manifests; the final segment may be partial. Progressive files cache only an opening prefix, with no trailing-moov lookup. Adaptive variants and separate audio may still need the network. The cap excludes HTTP headers and upstream range-skipping overhead. Pause, background, transitions, source changes, loading and exit cancel the worker; in-flight requests use a **3-second** timeout. Failures remain optional and do not loop. Real-world switch latency and zero-wait playback are not claimed.

## English validation and limits

**67 JVM tests**, both builds, **12 targeted emulator cases** and **9 safe N5 API-34 cases** passed. Emulator restore covers confirmation, cancellation, malformed files, disabled sources, empty lists and sparse progress; after one UI synchronization failure, the fixture waits for the completed settings refresh and passed twice. Actual N5 direction/confirm injection exported a backup with **9 sources and 6 history entries**, opened/cancelled the restore picker, and left original preferences byte-identical. The installed APK hash matches delivery. Three independent read-only reviews passed. The original light-off control was confirmed by its property and fresh backend events, without shutdown/standby or further input after closing.

Real-user replacement restore was not performed on N5. Physical-remote long presses, long playback, projector reboot, a genuinely powered-off Windows computer, FPS and outage recovery were not revalidated. The release also includes the initially-empty recommendation recovery fix. Use the commands above for test-only fixtures; `backup-roundtrip` is restricted to disposable emulators. Private preferences, real backups and raw logs are not published.
