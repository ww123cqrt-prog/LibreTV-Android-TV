# LibreTV 原生播放辅助功能实施计划

> 按用户已同意的三项功能执行；复用 projector-app-dev 的本地、模拟器、N5 分层验收流程，按 requesting-code-review 做独立审查。

**目标：** 增加实际视频分辨率、应用内备份恢复、下一集开头的有限预缓存，保持现有独立点播和遥控操作。

**架构：** 画质由 Media3 实际视频事件提供；Android 10 以上一键导出到“下载/LibreTV”，更老版本保留系统文件选择器；恢复经文件选择器读取带版本的 Android 自有 JSON，在完整解析后一次应用。预缓存与播放共用现有 SimpleCache，只预取紧邻下一集，当前播放缓冲优先。不开服务，不引入服务器，不升级 Media3，不复制上游 Web 播放器。

**基线：** `a1ea15c`，包含 0.1.8 空首批恢复分页修复。开发分支 `feat/native-player-tools`。

## 1. 播放画质与下一集预缓存

- [x] 修改 `app/src/main/java/com/dpadtv/client/PlayerActivity.kt`：在暂停／操作栏显示实际分辨率；未知时不伪造清晰度。换集、换源时避免显示旧来源尺寸。
- [x] 核对固定 Media3 1.5.1 的官方接口，优先借助已有媒体源和缓存，不引入额外解码播放器。
- [x] 如需独立工作单元，仅新增与单个下一集预取直接相关的文件；限制时间／数据量，当前缓冲不足、暂停、离页、换源时停止；失败不阻止正常播放。
- [x] 受控测试验证分辨率、实际缓存命中、预取范围、取消以及原有手动／自动换集与换源。由主代理统一执行 Gradle 和设备测试，避免并发修改构建产物。

实现取舍：使用既有 HLS 解析器与 CacheDataSource；HLS 目标约 12 秒，单次读取最多 4 MiB（含清单）。字节上限优先，最后一个大分片可只缓存前缀，由正常播放继续读取；不保证分片完整或零等待。MP4 只预取开头。磁盘写入失败遵从 SharedPreferences 的实际语义，界面区分运行态已应用与尚未持久化；文件读取／解析失败和取消不改配置，不加入低频磁盘回滚框架。

## 2. 备份与恢复

- [x] 新增 Android 自有备份编解码；保留片源启用状态、观看历史、续播断点、搜索历史和当前播放／首页设置。不包含废弃服务器密码、会话或视频缓存内容。
- [x] 修改 `Prefs.kt`：导出快照；恢复时先完整解析，再一次写入对应配置。空列表可准确恢复，不重新初始化默认片源。
- [x] 修改 `MainActivity.kt`：设置中提供“导出备份”和“恢复备份”；Android 10 以上导出经 MediaStore Downloads 一键保存，旧版经 `ACTION_CREATE_DOCUMENT` 选择位置；恢复经 `ACTION_OPEN_DOCUMENT` 选择文件，导入前显示片源、历史数量并确认覆盖。
- [x] 覆盖真实数据结构往返、旧备份版本／破损文件拒绝且配置不变、取消文件选择不改变配置。真实用户配置验收只做无损导出；替换恢复场景限可丢弃模拟器。

## 3. 集成与交付

- [x] 运行 JVM 测试和主／测试 APK 构建；通过后执行 TV 模拟器定向回归，再执行 N5 定向实测。
- [x] 独立审查数据恢复和播放生命周期；修正有证据的缺陷，不增加推测性框架。
- [x] 保持包名、显示名称 LibreTV 和原开发签名；同签名覆盖安装，保留用户片源、历史、缓存和原 DpadTV。
- [x] 记录实际测试、未覆盖范围、APK 校验值和复用命令；验收后按会话约定关闭光源。版本发布与实机通过分别记录，不用构建成功代替实机结果。

## English scope

Implement the three approved conveniences: actual decoded video dimensions, native backup/restore, and bounded warm-up of the next episode. Retain Media3 1.5.1, the existing native player and SimpleCache. Export sources, history, resume positions, search history and active app settings to Download/LibreTV on Android 10+; older versions use the document picker. Restore through the document picker, parse fully before applying and ask before replacement. Exclude obsolete server credentials and video cache data. Fetch only the immediately following episode, yield to foreground buffering, cancel on lifecycle/source changes, and keep failures optional. Validate JVM logic, disposable TV-emulator restore/playback cases, then N5 behavior without replacing real user preferences. Keep the original app identity, signature and DpadTV installation, and document evidence boundaries.
