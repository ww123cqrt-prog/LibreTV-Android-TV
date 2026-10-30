# LibreTV · 投影仪 / Android TV

这是基于 **[DpadTV v1.2.0](https://github.com/AresCNZJ/DpadTV/tree/v1.2.0)** 和 **[LibreSpark/LibreTV v2.21.1](https://github.com/LibreSpark/LibreTV/tree/v2.21.1)** 开发的投影仪／Android TV 版本，已在坚果 N5 Pro 上测试。沿用 DpadTV 原生界面、遥控操作和 Media3 播放，将 LibreTV 的 Apple CMS 搜索、JSON 详情、推荐标签和同名合卡能力移到 Kotlin 客户端。运行时由设备直接访问接口和视频，无需 Windows、NAS、Node.js 或自建服务器。

This is a projector / Android TV adaptation based on DpadTV and LibreSpark/LibreTV. It retains native remote navigation and Media3 playback, with Kotlin clients for catalogue and recommendation requests. This is a community adaptation, not an official release of either upstream project.

应用名称为 **LibreTV**，仓库名为 **LibreTV-Android-TV**。本项目是社区适配版本，与两个上游项目没有官方发布关系。开发来源、固定提交和修改说明见 [NOTICE.md](NOTICE.md)。

当前版本为 0.1.8，显示名称为 LibreTV，延续本适配版包名和开发签名。电影、剧集各有 10 个推荐标签；搜索结果按片名和年份合卡，详情页和播放器均可换源。目标是无需电脑的日常影视点播：推荐、全源搜索、选集、遥控播放和续播。直播、应用内更新等扩展功能已取消，不作为交付要求。

## 安装与使用

从 [Releases](https://github.com/ww123cqrt-prog/LibreTV-Android-TV/releases) 下载 APK。当前为 0.1.8 / versionCode 9 的调试预发布包，沿用已有开发签名。Android 最低要求为 6.0 / API 23，已测设备为坚果 N5 Pro（Android 14 / API 34，ARM64）；其他设备尚未逐一验收。

包名为 `local.libretv.standalone.tv`，与原版 `com.dpadtv.client` 并存，配置、历史和缓存分开保存。首次运行从 APK 内置文件载入 9 个可编辑的公开接口地址，以后不会覆盖手动修改；不读取其他应用的配置。这些第三方地址的可用性不由本项目保证，也不包含用户账号或登录凭据。

1. 安装对应 APK，在投影仪桌面打开 `LibreTV`。
2. 首页可切换电影、剧集、综艺。电影与剧集下有一排分类标签：左右移动、OK 选择，上下在主导航、分类和海报之间移动。点击海报会按片名搜索全部启用片源，返回首页保留当前分类；也可点“搜索”自己输入片名。
3. 全部已启用来源一起搜索，快来源先显示，慢来源继续补充。同名影片合成一张卡，同名翻拍按年份分开；卡片显示资源数和来源。点卡片默认打开第一条已返回资源的集数，详情页来源按钮可切换，返回会回到原卡片；无需先选来源。
4. 播放时 OK 暂停或继续，左右快退或快进，下键打开操作栏，上键调整倍速，返回键退出播放。操作栏首项是“换源”：搜索结果到达后立即并行验证详情，先验证完成的来源先出现，其余继续补充。当前影片有年份而候选年份未知时，须等待完整搜索核对同名翻拍。选定后保留集数、时间和播放／暂停状态；缺少当前集则保留原播放，切换时首帧失败或等待超过 15 秒则尝试恢复原来源。
5. “继续观看”读取本应用的历史和进度。“片源”提供添加、停用和测试；菜单键进入本地设置。

首页接近已加载列表末尾前约两屏时，会静默读取下一批，并提前准备后约两屏的封面；追加到原海报墙，保留焦点与滚动位置。换分类、离开页面或进入后台后停止后续预读。预读失败保留当前列表，在末尾继续按下键即可重试。范围和测试见 [0.1.8 预加载记录](docs/recommend-prefetch-0.1.8.md)。

片源仍需要互联网。封面、目录与视频分别请求各自的外部地址。App 不使用 `127.0.0.1:18080`，不需要 ADB reverse。

也可通过已经连接的 ADB 安装，将示例中的设备序列号替换为 `adb devices` 列出的实际值。无线端口以当前设备状态为准：

```powershell
$serial = 'YOUR_DEVICE_SERIAL'
adb -s $serial install -r '.\LibreTV-0.1.8.apk'
adb -s $serial shell am start -W -n local.libretv.standalone.tv/com.dpadtv.client.MainActivity
```

## 已验证与差异

| 功能 | 当前状态 |
| --- | --- |
| Apple CMS 多源搜索、JSON 详情、播放列表 | 已移植；历史测试样本见性能报告，不将单次片源成功率作为当前可用性承诺 |
| LibreTV 片名相关性、季度别名、线路选择 | 与固定 v2.21.1 原代码生成的 22 个样例对照；Android 额外接受数字年份，该差异由独立测试验证 |
| HTTP 到 HTTPS 跳转 | 已支持；360、极速片源实测通过 |
| 遥控导航、原生 HLS 播放、快进 | 新包实机通过；沿用 DpadTV 播放器 |
| 播放缓存、观看历史、续播 | 沿用；产生缓存，重启与同版本覆盖安装保留进度 |
| 全源搜索、同名合卡 | 快结果逐页显示并追加；按精确片名、年份合并，详情页提供已返回资源切换，默认直接聚焦集数 |
| 播放中换源 | 0.1.6 新增；沿用同一个播放器，查询时保持原播放；保留集数、断点及暂停状态，首帧失败回退 |
| 文本、网址、文件及 LibreTV 设置导入 | 沿用 DpadTV 解析；尚未逐格式完成实机验收 |
| HTML 详情、网页播放器解析、特殊源代理 | 未实现；没有直链时明确提示 |
| 电影、剧集、综艺推荐及搜索热榜 | 已直连豆瓣；N5 每类48条，三类封面样本完整解码，海报墙显示通过 |
| 上游电影、剧集推荐标签 | 0.1.4 新增各10个；N5 20个标签均返回48条，每类首张封面完整解码通过；详细范围见分类验收记录 |
| 推荐下一批与封面预加载 | 0.1.8 新增；保留原网格、焦点和滚动位置，限制屏外下载；详见预加载记录 |
| 手机扫码导入的局域网 HTTP 服务 | 已停用，可用文本、文件或 URL 导入 |
| 真正关闭 Windows、投影仪重启、断网恢复、长时间连续播放 | 待实机验收 |

第三方片源可用性随网络和时间变化，实测细节和本轮检查结论见 `docs/performance-audit-2026-10-28.md`。

0.1.4 分类范围见 [分类验收记录](docs/recommendation-tags-0.1.4.md)。0.1.5 的分组规则见 [合卡验收记录](docs/aggregate-search-0.1.5.md)。0.1.6 的操作和验证范围见 [播放换源验收记录](docs/player-source-switch-0.1.6.md)。来源列表中的响应时间测量详情接口，不代表视频带宽；不同来源的片头和剪辑可能不同，按秒续播不能保证画面完全对齐。

搜索先显示已返回结果，其余来源继续补充；初次查询和关键词自动清洗后的重搜共用约 12 秒预算，后续页面超时会保留成功页。换源随搜索进度验证详情，最多同时验证 4 个；历史页返回即刷新，相同封面合并在途下载。0.1.6 审查中的 8 项 P2 修复及证据见 [0.1.7 修复记录](docs/review-fixes-0.1.7.md)，原始发现保留在 [四角度审查记录](docs/review-2026-10-30.md)。

## 构建与复测

构建环境为 JDK 21、AGP 9.3.0、Android SDK Platform 37。仓库 wrapper 固定为已验证的 Gradle 9.8.2，并配置官方发行包 SHA-256；初次使用需联网下载 Gradle 和依赖。以下命令使用 Windows PowerShell 7，`java` 和 `keytool` 应来自 JDK 21。先设置 `ANDROID_HOME` 为自己的 SDK 目录，或在未跟踪的 `local.properties` 中填写 `sdk.dir`。

```powershell
git clone https://github.com/ww123cqrt-prog/LibreTV-Android-TV.git
Set-Location LibreTV-Android-TV
pwsh -File .\tools\Initialize-Signing.ps1
# 首次创建本地签名；今后必须保留原来的 .signing 目录。
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。macOS／Linux 可用 `./gradlew`，签名初始化脚本同样需要 PowerShell 7；这些平台未在本轮验证。

Node.js 只用于可选的对照样例再生成：`node ./tools/create-libretv-oracle.mjs`。固定样例已随仓库提供，普通编译和运行不需要 Node.js。再生成需要支持 TypeScript 类型擦除的 Node.js，已有样例使用 v24.11.1 生成。

设备测试 APK 只用于开发验证，主 APK 无测试入口。测试设备须先安装本次使用同一 `.signing` 构建的主 APK；自行生成的签名不能用于测试 Releases 签名的主 APK。以下命令仅在已安装本次同签名主 APK 的测试设备上执行：

```powershell
adb -s $serial install -r '.\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
adb -s $serial shell am instrument -w -r -e query 三体 local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
```

推荐与封面的额外实机复测：

```powershell
adb -s $serial shell am instrument -w -r -e mode recommend local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
```

播放换源的受控复测使用测试 APK 内自行生成的 12 秒短片。测试临时 HTTP 服务只绑定设备的 `127.0.0.1`，结束后关闭，不依赖电脑服务。生产 APK 不包含短片或测试入口。

```powershell
adb -s $serial shell am instrument -w -r -e mode switch-player local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
```

本轮回归入口仅用于可丢弃的模拟器：`r1`、`r7` 临时替换历史和片源，`r5` 临时替换片源和搜索历史，结束后还原；中途强杀测试进程可能来不及还原。不要在日常使用设备上运行这些夹具。

```powershell
adb -s emulator-5554 shell am instrument -w -r -e mode regression -e case r4 local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
# case: r1 / r1-seek / r2 / r3 / r4 / r4-unknown / r5 / r7 / r8
```

推荐列表直连 `movie.douban.com`，图片直连 `doubanio.com` 子域；只传公开的 User-Agent 和 Referer，不需要豆瓣登录，也不走电脑图片代理。

测试调用生产 Kotlin API，报告每个源的分类错误、耗时、结果数、详情集数和前 2048 字节媒体列表请求。媒体列表 HTTP 200 不能代替真正解码播放；真实播放另见验收报告。当前 JVM 测试为 60/60，在 0.1.6 的 56 项基础上增加 3 项数字年份兼容性测试和 1 项共享搜索期限测试。模拟器回归另覆盖历史刷新、跨集快进、后台保存、渐进换源、重搜预算、补封面和图片请求合并。

## 签名、源码与许可证

本地独立签名保存在 `.signing`，不进入 Git 或源码 ZIP。不要重新生成已用于安装的密钥，否则无法覆盖更新并保留现有数据。源码接收者首次初始化会生成自己的签名，不能直接更新此处交付的已签名 APK。

DpadTV 部分保留 [GPL-3.0](LICENSE)；LibreTV 移植部分按 [AGPL-3.0-or-later](third_party/LibreTV/LICENSE) 及其原声明提供。组合发布保留两者要求，完整对应源码、固定参考代码、构建脚本及来源说明均在本仓库。[GPLv3 第 13 节](https://www.gnu.org/licenses/gpl-3.0.html#section13) 说明了与 AGPLv3 组合的条款；不要将本项目描述为全部仅有 GPL-3.0。

各依赖说明见 [THIRD-PARTY.md](THIRD-PARTY.md)。仓库保留上游作者和资源归属；本地签名密钥、个人设置、日志及播放历史备份不公开。
