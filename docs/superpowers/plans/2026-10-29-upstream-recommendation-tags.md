# 上游推荐分类补齐 / Upstream recommendation tags

**Goal / 目标：** 按用户已确认的逐步补齐方案，先将 LibreTV v2.21.1 的电影、剧集标签接入现有原生首页。

**Architecture / 实现：** 在现有导航与海报墙之间增加横向标签栏，复用 `Api.douban(type, tag, pageStart, pageSize)`、分页和全源搜索。选中标签与遥控焦点分别显示；切换标签重置分页，返回首页保留当前分类。保留综艺入口、应用名称、包名、签名与用户数据。

**Scope / 范围：** 采用上游现成标签，不增加未经验证的类型或院线、独播、获奖片单；同名合卡、播放中换源属于后续工作。

**Tech Stack / 技术：** Kotlin、Android 原生控件、既有 Douban 直连接口与 Gradle 构建。

- [x] 修改 `MainActivity.kt` 的标签栏、分页重置与上下左右焦点路径；版本升至 0.1.4 / 5。
- [x] 扩展现有测试 APK 的 recommend 模式，允许指定 type/tag，验证真实接口；不增加运行时测试入口。
- [x] 运行现有核心测试、构建主 APK 与测试 APK；在 TV 模拟器验证标签选择、首尾导航、海报往返和搜索返回。
- [x] 通过模拟器后，同签名覆盖更新 N5，核对配置、历史、进度及原 DpadTV 保留；记录实际覆盖范围与 APK。

Source / 来源：<https://github.com/LibreSpark/LibreTV/blob/v2.21.1/src/components/douban-section.tsx#L11>。
