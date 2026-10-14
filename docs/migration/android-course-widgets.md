# Android 课表小组件

仅保留荣耀 MagicOS 卡片适配；小米、OPPO、vivo、Google Pixel 等设备统一使用标准 Android 小组件。华为专有卡片不在范围内。

## 功能与数据

- 临近课程：小号纵向显示正在上/下一节，中号两栏；今日上完后切到下一个有课日。整周休息时不会把很远以后的课程当成马上要上的课。
- 今日课程：按时间显示今天全部课程；上完后切到下一个有课日。大尺寸增加地点、教师；放不下时显示剩余节数。
- 本周课表：周一至周日，按学校大节排列，空的大节不占行；考试、实验按实际时段与大节交集显示，非标准时段和装不下的课程计入脚注。
- 类别色复用 `CourseCategoryPalette`，浅/深色资源镜像由校验脚本核对。所有内容均为原生 `RemoteViews`，不是截图或 WebView；支持内容描述、系统字体缩放和中文/英文。
- 主应用已有的防抖写入链生成课程、实验、考试快照。Android 写入 `noBackupFilesDir/course-widget.json`，用 `AtomicFile` 原子替换；退出登录删除快照并更新组件。组件不读取凭据、成绩或数据库，也不登录/访问网络。
- Widget 在 `:widgetProvider` 进程运行，只读取快照和渲染；不初始化 Compose、WebView 或验证码模型。读取不缓存，并校验版本、日期、时段、文件大小。损坏/缺失/过期快照显示打开应用更新的提示。
- 点击卡片使用不可变 `PendingIntent.getActivity`；冷启动或登录恢复后进入课程表。刷新按钮只重算本地课程时间状态，不代表重新从学校同步数据。

## 平台接入与实际交付边界

| 平台 | 本次代码接入 | 验证边界 |
| --- | --- | --- |
| 标准 Android（小米、OPPO、vivo、Pixel 等） | 三个可缩放 AppWidget provider；Android 12+ 按宿主给出的 `OPTION_APPWIDGET_SIZES` 提供多尺寸 RemoteViews，旧系统提供横竖屏两套；预览图、`previewLayout`、Android 15+ 脱敏示例数据生成预览、系统圆角 | Android 9+；各厂商桌面的实际呈现仍需真机验证 |
| 荣耀 MagicOS | 同一组三个 provider 添加 `com.hihonor.widget.type=honorcard`，保留标准 `android.appwidget.provider` 以兼容旧系统和其他桌面；响应式宽度与横竖屏 | 官方卡片接入标识已实现；系统卡片识别、快服务推荐/审核及折叠屏行为需平台和真机验证 |

所有品牌只注册同一组三个组件，均默认可用，不按品牌动态开关。临近课程支持小号/宽版，今日课程支持紧凑/详细版，本周课表使用大尺寸。没有小米/vivo 专有组件入口、曝光广播或厂商专用刷新周期；不引入 OPPO Seedling SDK，也不依赖这些厂商组件库的收录审核。

## 时间与刷新

- 主 App 写入、用户刷新、添加/缩放/恢复、开机、升级、日期/时间/时区/配置变化会触发本地更新。
- 三个组件均使用标准 Android 的30分钟被动更新周期。
- 组件安排下一次上下课或午夜的非精确 `AlarmManager.setWindow`，窗口10分钟，不申请精确闹钟，不启动常驻服务。系统省电/Doze可能继续推迟，**不能保证课堂边界按秒刷新**。
- 快照默认覆盖本周一至未来14天，过期后要求打开主 App 同步。它只反映上次同步结果，学校临时调课必须先在 App 更新。

## 官方文档（2026-10-13 查阅）

- Google：[创建 AppWidget](https://developer.android.com/develop/ui/views/appwidgets/overview)、[灵活布局](https://developer.android.com/develop/ui/views/appwidgets/layouts)、[组件预览](https://developer.android.com/develop/ui/views/appwidgets/previews)、[设计规范](https://developer.android.com/design/ui/widget)。
- 荣耀：[安卓卡片接入开发指南](https://developer.honor.com/en/doc/guides/100171)。遵循官方标识、主题、响应式布局约定；Google 回退保留系统字体缩放，不采用荣耀文档中将所有字号改为 dp 的建议。

## 验证方式

```bash
cd multiplatform
python3 scripts/validate-android-widgets.py
./gradlew :shared:desktopTest
./gradlew :shared:compileAndroidMain :androidApp:assembleDebug :androidApp:lintVitalRelease :androidApp:testDebugUnitTest
```

Android 构建需要仓库既有的签名配置；PR CI 使用独立 compile-only fixture，不改现有签名策略。新增 Robolectric 4.17 仅用于 Android 单元测试，不进入 APK。

本轮收敛范围后，提交 `8a9d386` 的 `:shared:compileAndroidMain :androidApp:assembleDebug :androidApp:testDebugUnitTest` 通过（9项、0失败、0跳过，[CI run 68](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/38491090683/job/115616182672)）。本地静态检查验证三个标准 provider、荣耀标识、预览、主题与配色，`git diff --check` 通过。完整构建和运行测试仅经 CI 验证，本地环境缺 javac/Android SDK。后续仅补充验证记录的提交不改变产品代码。

共享代码本轮未改动；此前提交 `3fc0c2f` 的 iOS 模拟器测试及 macOS `desktopTest` 已通过（[CI run 64](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/38432784294)）。

共享测试覆盖上下课边界、次日/跨周、空周/过期、异常 JSON。Android 测试实际执行 `RemoteViews.apply`，覆盖全部 provider 的资源、浅/深色、放大字体、旧/新 API 和剩余计数；文件读取测试覆盖重新写入、损坏、删除。这些测试不证明 OEM 桌面中心收录、真机进程内存或桌面 UI 成色。

真机验收至少包括：三种组件/五种尺寸、浅深色、字体1.0/1.5、4×6/5×6网格、横竖屏与折叠展开、同时添加多个实例、冷启动点击、账号退出、清应用数据、午夜/上下课/改时区、超过快照覆盖期、荣耀旧系统/第三方桌面的标准组件兼容性。用 `adb shell dumpsys meminfo team.bjtuss.bjtuselfservice.kmp:widgetProvider` 记录厂商进程内存；截图使用脱敏课表。
