# LibreTV 0.1.7：审查问题修复与验证

2026-10-30，修复 [0.1.6 审查记录](review-2026-10-30.md) 中的 8 项 P2。版本为 **0.1.7 / versionCode 8**，包名和签名延续原适配版。原版 DpadTV 保留。

## 逐项修复

各项先复现失败，再运行同一场景验证。代码按问题分别提交；R1 在真实网络下追加了缓冲期间返回的修复。

| 编号 | 修复前证据 | 最小修改与结果 | 提交 |
| --- | --- | --- | --- |
| R1 历史显示 | 立即返回后仍显示旧进度；实机追加发现快进尚在缓冲时会跳过保存 | 在 onPause 保存，返回时刷新原适配器并保留所选地址；已知时长的缓冲快进也保存目标位置。READY 与 BUFFERING 两个场景均通过 | [cd70d9e](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/cd70d9e)、[301d00d](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/301d00d) |
| R2 跨集快进 | 第二集第一次快进错误跳到 7000ms | 换集时清旧目标及延迟清理任务；相同操作落在第二集 1000ms | [71976e5](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/71976e5) |
| R3 后台写入 | 暂停并调用停止生命周期后，观看时间仍周期更新 | onStart / onStop 成对启停保存任务；停止后等待 11 秒，时间不变，恢复可见生命周期后继续保存 | [8a4c529](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/8a4c529) |
| R4 换源等待 | 快源详情被阻塞的慢搜索拖住 | 使用现有搜索进度去重提交详情，保留最多 4 并发；慢源未释放时，快候选已出现，最终受控测试为 297ms | [2fa3dd0](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/2fa3dd0) |
| R5 重搜预算 | 13.5 秒时仍未结束，清洗关键词重搜另开预算 | 初次与重搜共用一个绝对截止时间；延迟源测试约 12.16 秒结束 | [dff4355](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/dff4355) |
| R6 数字年份 | JSON 数字年份变为空，翻拍合卡和换源匹配失效 | 仅在 JSON 边界接收字符串或数字年份；1984 / 2021 分组、详情年份和未知类型测试通过 | [bab07b0](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/bab07b0) |
| R7 历史补图 | 两条缺图历史出现 A=1、B=2 次搜索，并重建页面 | 一个最多 5 条的补图批次，更新原适配器；A、B 各搜索一次，焦点保持 | [a1dc5b6](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/a1dc5b6) |
| R8 同图下载 | 9 次绑定发出 9 次请求 | 主线程合并相同 URL 和尺寸的在途请求，沿用现有图片线程池；9 个回调仅 1 次下载 | [945c98a](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/commit/945c98a) |

以上耗时来自受控本地端点，不代表互联网片源的延迟或视频带宽。年份缺失且有同名翻拍歧义的候选仍须等待完整结果；`r4-unknown` 在修改前后均通过，是保留行为的回归护栏。

## 构建与模拟器

- JDK 21、Gradle 9.8.2、API 34 Android TV 模拟器；主 APK、测试 APK 构建成功。
- JVM **60/60**，无失败、错误或跳过。固定 LibreTV v2.21.1 的 22 个样例保持原文件；Android 数字年份差异由 3 个独立测试覆盖，另增加 1 个共享截止时间测试。
- 新增回归入口 **9/9**：`r1`、`r1-seek`、`r2`、`r3`、`r4`、`r4-unknown`、`r5`、`r7`、`r8`。
- 原有换源场景 **4/4**：暂停切换、无效源回退、播放中切换、缺集保留；测试前后偏好不变。
- 历史夹具先取消启动推荐请求和弹窗，避免其联网回调干扰夹具页面；换源夹具正常处理播放器取消旧 HTTP 请求。两项只修改测试 APK。
- 两轮独立只读复核通过；补充的缓冲保存条件也单独复核，未发现需要阻止提交的问题。

R3 通过 Instrumentation 调用真实 Activity 生命周期，证明计时任务停止与恢复；本轮没有把它表述为 N5 真实后台留存测试。

## 坚果 N5 Pro 实测

设备 Android 14 / API 34、ARM64。仅升级 LibreTV，不修改系统、电源、光源、输入法或原版 DpadTV。

1. 从 0.1.6 / 7 同签名覆盖到 0.1.7 / 8；安装前后应用偏好文件逐字节一致，保留 **9 个片源、6 条历史**。追加补丁的覆盖安装也保留数据。
2. 首页推荐正常，真实电影成功解码出画面；从历史续播并打开换源，在“继续查找中”时已显示可选来源。
3. 最终 APK 再次真实播放，暂停后快进 30 秒，约 **735ms** 内返回；历史立即显示增加后的断点，与保存值一致。
4. 最终测试 APK 的原有 **4 个换源场景通过**，偏好不变。原版 DpadTV 仍为 1.2.0，其首次安装和更新时间未变。

主 APK 中未包含测试短片、测试入口或回归测试类。APK 保持同一签名，证书 SHA-256 为 `3edd8eca1250b5cbb67794c9544e2da5ad6e67490e03ab015454b0be5a15d1e6`；文件校验值随 Release 提供。

## 范围与复测

没有新增常驻服务、代理、重试框架或运行时。P3 持续慢速响应正文风险尚无实际片源症状，本轮未增加防御代码。HTML / 特殊片源代理、跨源剪辑差异仍按原能力边界处理。

本轮未执行关闭 Windows、投影仪重启、长时间播放和断网恢复验收；不据此宣称这些场景通过。运行路径仍为设备直接访问推荐、CMS 与视频接口，不使用电脑服务或 ADB reverse。

构建和测试命令见 [README](../README.md#构建与复测)。会临时替换历史或片源的回归入口只在可丢弃模拟器上运行，结束后还原；中途强杀可能来不及还原。私有偏好备份、完整日志和现场截图留在本地，不进入公开仓库。

## English summary

Version 0.1.7 fixes all eight reviewed P2 issues, with separate commits and failing-to-passing regression evidence. It also fixes saving a seek position when exiting during buffering. Validation: 60 JVM tests, nine Android emulator regression cases, four existing source-switch cases on both emulator and N5 Pro, a data-preserving upgrade, real video playback and an immediate seek-and-return check on N5 Pro. Background persistence was tested through Instrumentation lifecycle calls, not a new physical-device background-retention trial. Windows-off, projector reboot, long playback and network recovery remain unverified in this round.
