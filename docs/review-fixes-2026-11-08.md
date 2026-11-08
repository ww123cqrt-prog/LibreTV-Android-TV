# 2026-11-08 审查修复

固定审查基线：`370f5acc34d567b87de59925bbf9e0e3cb53d84a`。本轮只在 Android 14 TV 模拟器验证，没有连接或部署坚果 N5 Pro，也没有发布新的 Release。

| 问题 | 修复及模拟器结果 |
| --- | --- |
| 重播后前 5 秒退出保留旧断点 | 显式重播清除当前地址的旧进度；重播、退出、重新打开从开头播放通过 |
| 空年份来源换到有年份来源后收藏身份不一致 | 首帧成功后复用历史恢复已有的年份补全和收藏迁移；保留单条收藏，失败换源不改变身份 |
| 清理缓存遗漏播放磁盘缓存 | 在后台调用已有 PlaybackCache.clear；真实缓存片段从非零清到零，片源配置保留 |
| 片源内部刷新丢失返回设置入口 | 内部刷新和导入子页返回保留入口，显式点击一级片源导航仍进入一级页面 |

四项均先在修改前的模拟器复现，再验证修复。新增入口位于测试 APK 的 `ReviewFixesProbe`，使用已有生成短片和只绑定回环地址的 HTTP 测试服务，不请求真实片源。测试会恢复替换的应用配置，只允许指定型号的可丢弃 TV 模拟器运行。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
# 先安装本次构建的主 APK 和测试 APK；必须明确指定模拟器序列号。
adb -s <emulator-serial> shell am instrument -w -r -e mode regression -e case review-replay local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
```

新增 case：`review-replay`、`review-favorite-switch`、`review-clear-cache`、`review-sources-back`，四项通过。原有 `tv-navigation`、`tv-favorites-history` 两项回归通过；74 项 JVM 测试通过。构建仍有既有 Gradle 弃用提示，不代表所有编译输出均无警告。

真实遥控器、N5 固件、真实片源和长时间播放不在本轮验收范围内。运行包名、签名配置、已有数据格式及上游正式运行环境保持原有约定。
