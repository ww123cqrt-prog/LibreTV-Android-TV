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

## 第二轮 P2：清理完成覆盖新页面

复现基线：`6a2f70b1492ee4a9e074da959e5398a159df62a5`。

在测试线程暂时持有 `PlaybackCache` 已有的锁，确认清理后通过顶部导航切到收藏或搜索，再放行真实磁盘清理。原代码的两项测试分别报错 `Cleanup changed favorites to setup`、`Cleanup changed home to setup`：清理完成后无条件刷新设置，覆盖了用户的新页面。

业务代码只修改一行：仅当 `currentScreen == "setup"` 时刷新设置。清理和完成提示仍正常执行。时序控制全部放在测试 APK，没有新增业务状态或测试专用接口。

修复后，在 Android 14 TV 模拟器通过以下回归；缓存清零，片源配置保留：

- `review-clear-cache-favorites`：清理期间转到收藏，完成后保留收藏页。
- `review-clear-cache-search`：清理期间转到搜索，完成后保留搜索页。
- `review-clear-cache`：留在设置页，正常完成清理和刷新。
- `tv-navigation`：顶部图标、返回层级和设置导航正常。

沿用上方构建和测试命令，74 项 JVM 测试通过，主 APK 和测试 APK 构建成功。包名、版本和签名保持一致；本轮只覆盖安装到模拟器，没有操作或部署投影仪。构建仍提示已有的弃用 API 警告。
