# LibreTV 0.1.8：推荐预加载

2026-10-30，沿用 Peach 的有界预读思路，提前准备推荐列表和封面，减少到列表边界时的等待。版本 **0.1.8 / versionCode 9**；包名、显示名称和签名不变。

## 行为

- 距已加载列表末尾约两屏时静默读取下一批，每批 48 条；仍只有一个推荐请求在途。
- 在原 GridView 上追加数据，更新数量，不再弹“加载更多”或重建海报墙；保留所选条目和滚动位置。
- 提前准备后约两屏的封面，最多 48 张；预读与实际卡片都按 180×260 dp 请求，共用现有内存缓存和在途请求合并。
- 复用原有 3 个图片线程，一次最多启动 1 个屏外图片预读。窗口变化时替换待处理队列，不累积整页任务；同一窗口的失败图片不循环预读。
- 换分类、离开页面或 onPause 时取消推荐任务、清空尚未开始的图片预读。已经在途的一张图片可完成并进入缓存，但不再接续下载；迟到的推荐结果不覆盖当前页面。
- 下一批失败保留现有列表，不循环自动重试；到列表末尾再按下键即可重试。没有新增设置、图片库、服务或电脑依赖。

## 验证

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | 60/60，无失败、错误或跳过 |
| API 34 TV 模拟器 | 6/6：首批焦点、屏外封面、静默分页、离页取消、暂停/恢复、失败后下键重试 |
| 相关旧回归 | 图片 9 次绑定仅 1 次请求、历史返回刷新、两条历史各补封面一次，3/3 |
| N5 Pro 受控测试 | 同上 6 个预加载场景通过，调用生产 API 与真实卡片绑定，使用设备本地测试端点 |
| N5 Pro 真实推荐页 | 首批 48 条，正常向下浏览时提前追加至 96 条，焦点留在海报墙，无“加载更多”弹窗 |
| 升级与数据 | 从 0.1.7 同签名覆盖；安装及全部测试后偏好文件与测试前逐字节一致，保留 9 个片源、6 条历史 |

旧版的屏外封面与提前分页测试先失败，再改动。小范围独立审查还发现预读尺寸未对齐真实卡片、首批焦点未恢复；补充的真实绑定及首批焦点测试均先失败，修正后通过。真实绑定时预读封面仅请求一次，确认复用同一个缓存键。

测试没有替换用户片源或历史。测试短片、回归入口及本轮推荐夹具均不进入主 APK。N5 初次测试被原厂 Dream 屏保挡在后台，未布局；退出屏保并核对实际前台后复测通过，没有据此增加应用防御代码。

暂停/恢复场景通过 Instrumentation 调用 Activity 生命周期，遥控导航通过 ADB 注入；不能代替实体遥控器长按或长时间后台留存验收。预读减少常规等待，网络过慢或快速跳过预读窗口时仍可能需要加载；本轮未测 FPS，不宣称所有卡顿均已消除。

## 复测

安装同签名主 APK 与测试 APK后，在测试设备运行：

```powershell
adb -s $serial shell am instrument -w -r -e mode regression -e case prefetch-page local.libretv.standalone.tv.test/com.dpadtv.client.NativeCmsInstrumentation
# case: prefetch-initial / prefetch-covers / prefetch-page / prefetch-scope / prefetch-background / prefetch-retry / prefetch-empty-resume
```

完整日志、偏好备份和截图保留在本地，不进入公开源码。

## 后续修复：首批为空后的分页恢复

2026-10-30，核对 dots 对固定提交 `fc64b6901c1330824c456bda9f88e4d42f378fb8` 的 P2 报告，并在 API 34 TV 模拟器复现：首次推荐为空，暂停／恢复后第一页返回 48 条，但旧的 `noMoreRecommend` 标记阻止继续分页。生产代码仅增加一行，在非空加载成功时将该标记重置为 `false`。

新增 `prefetch-empty-resume` 场景复用现有本地 HTTP 夹具与真实 Activity：首批为空 → 调用暂停／恢复生命周期 → 返回 48 条 → 向下浏览后追加至 96 条。修改前明确失败于“Recovered first page did not resume pagination”，修改后通过。JVM 测试 60/60、推荐预加载模拟器场景 7/7 通过，包含原有六项；每项均检查偏好未变。

本次没有增加版本号、发布新版本或部署 N5 Pro；上述修复的实机验收尚未执行。原文六项 N5 结果仍只对应发布的 0.1.8。

## English summary

Version 0.1.8 silently fetches the next 48 recommendations roughly two viewports before the loaded boundary, appends to the existing grid, and warms up to two offscreen cover viewports (48 images maximum). One image warm-up shares the existing loader, dimensions and cache with visible cards. Navigation and lifecycle changes stop further prefetching; failed pages retain the list and can be retried with Down at the end. Validation includes 60 JVM tests, six emulator and six N5 fixture cases, three related regressions, a real 48-to-96-item recommendation browse on N5 and unchanged user preferences. It reduces waiting without promising zero stalls on slow networks.

The 2026-10-30 follow-up confirms the dots P2 report against `fc64b6901c1330824c456bda9f88e4d42f378fb8`: an initially empty page left the end-of-list flag set after a successful foreground retry. A one-line reset on a non-empty response fixes pagination. The new `prefetch-empty-resume` regression failed before the fix and passed afterward, verifying 0 → pause/resume → 48 → 96 items. All 60 JVM tests and seven emulator prefetch cases passed, with unchanged preferences. This follow-up has no version bump, release or N5 deployment; its real-device acceptance is pending.
