# Android 课表小组件

本次范围为 Google 标准 Android、小米、OPPO、vivo、荣耀。用户已取消华为适配。

## 功能与数据

- 临近课程：小号纵向显示正在上/下一节，中号两栏；今日上完后切到下一个有课日。整周休息时不会把很远以后的课程当成马上要上的课。
- 今日课程：按时间显示今天全部课程；上完后切到下一个有课日。大尺寸增加地点、教师；放不下时显示剩余节数。
- 本周课表：周一至周日，按学校大节排列，空的大节不占行；考试、实验按实际时段与大节交集显示，非标准时段和装不下的课程计入脚注。
- 类别色复用 `CourseCategoryPalette`，浅/深色资源镜像由校验脚本核对。所有内容均为原生 `RemoteViews`，不是截图或 WebView；支持内容描述、系统字体缩放和中文/英文。
- 主应用已有的防抖写入链生成课程、实验、考试快照。Android 写入 `noBackupFilesDir/course-widget.json`，用 `AtomicFile` 原子替换；退出登录删除快照并更新组件。组件不读取凭据、成绩或数据库，也不登录/访问网络。
- Widget 在 `:widgetProvider` 进程运行，只读取快照和渲染；不初始化 Compose、WebView 或验证码模型。读取不缓存，并校验版本、日期、时段、文件大小。损坏/缺失/过期快照显示打开应用更新的提示。
- 点击卡片使用不可变 `PendingIntent.getActivity`；冷启动或登录恢复后进入课程表。刷新按钮只重算本地课程时间状态，不代表重新从学校同步数据。

## 厂商接入与实际交付边界

| 平台 | 本次代码接入 | 上线/验证边界 |
| --- | --- | --- |
| Google / Pixel / 原生 Android | 三个可缩放 AppWidget provider；Android 12+ 按宿主给出的 `OPTION_APPWIDGET_SIZES` 提供多尺寸 RemoteViews，旧系统提供横竖屏两套；预览图、`previewLayout`、系统圆角 | Android 9+；OEM 桌面仍需真机验证 |
| 小米 HyperOS / MIUI | 2×2 临近、4×2 临近、4×2 今日、4×4 今日、4×4 本周；`miuiWidget`、应用级 `miuiWidgetVersion`、独立进程、曝光广播、静态夜间资源、ID 迁移完成确认；根节点 `@android:id/background` | 小部件中心收录仍需小米开发者平台申请和审核；本 PR 不表示已获审核。需真机确认 <35MB 内存、4×6/5×6 网格和曝光刷新 |
| vivo OriginOS | 相同五种固定尺寸；`vivo_widget`、每 provider 的 `vivoWidgetVersion`/`vivo.widget.description`，`targetCellWidth/Height`、禁用 resize、被动更新间隔12小时；不传 bitmap、不使用滚动容器 | 原子组件库收录与一键添加须平台审核；需真机确认桌面内存限制、不同网格与组件库的浅/深色预览 |
| 荣耀 MagicOS | 通用 provider 添加 `com.hihonor.widget.type=honorcard`，保留标准 `android.appwidget.provider` 以兼容旧系统和其他厂商；响应式宽度与横竖屏 | 原生卡片接入标识已实现；快服务推荐/审核及折叠屏行为需平台和真机验证 |
| OPPO ColorOS | 本次完成标准 Android Widget 回退，布局响应式、支持浅/深色和本地刷新 | **专有泛在卡片尚未接入**。官方提供 SeedlingSupportSDK、UPK 卡片包与卡片开发工具体系；当前未取得可验证的 SDK/官方示例/打包工具和项目接入参数。取得这些材料后可沿用快照继续接入，不以虚构 metadata、反射私有接口或把 Android Widget 改名冒充泛在卡片 |

专用小米/vivo provider 默认关闭，主 App 在对应品牌第一次启动时开启。通用组件始终保留，旧系统、未审核、非厂商桌面可选择 Android 小部件回退。其他品牌不会出现额外十个 OEM 组件。

## 时间与刷新

- 主 App 写入、用户刷新、添加/缩放/恢复、开机、升级、日期/时间/时区/配置变化会触发本地更新。
- 通用组件被动更新周期30分钟；小米额外接收曝光更新，最小间隔60秒。
- 通用/小米组件安排下一次上下课或午夜的非精确 `AlarmManager.setWindow`，窗口10分钟，不申请精确闹钟，不启动常驻服务。系统省电/Doze可能继续推迟，**不能保证课堂边界按秒刷新**。
- vivo 原子组件遵循12小时被动周期；不通过边界闹钟绕过规范。需要即时重算时点刷新，或打开主 App；应用写入新快照会主动更新。
- 快照默认覆盖本周一至未来14天，过期后要求打开主 App 同步。它只反映上次同步结果，学校临时调课必须先在 App 更新。

## 官方文档（2026-10-13 查阅）

- Google：[创建 AppWidget](https://developer.android.com/develop/ui/views/appwidgets/overview)、[灵活布局](https://developer.android.com/develop/ui/views/appwidgets/layouts)、[组件预览](https://developer.android.com/develop/ui/views/appwidgets/previews)、[设计规范](https://developer.android.com/design/ui/widget)。
- 小米：[技术规范与系统能力](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584)、[HyperOS 设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1664)、[适配示例](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1585)、[提交审核流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1588)、[常见问题](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591)。
- vivo：[设计理念](https://dev.vivo.com.cn/documentCenter/doc/834)、[产品规范](https://dev.vivo.com.cn/documentCenter/doc/841)、[原子组件适配技术规范](https://dev.vivo.com.cn/documentCenter/doc/845)。技术规范的完整示例经官方公开文档接口读取核实。
- 荣耀：[安卓卡片接入开发指南](https://developer.honor.com/en/doc/guides/100171)。遵循官方标识、主题、响应式布局约定；Google 回退保留系统字体缩放，不采用荣耀文档中将所有字号改为 dp 的建议。
- OPPO：[Seedling API / 泛在服务文档](https://open.oppomobile.com/new/developmentDoc/info?id=13330)、[官方文档检索中的版本与 SDK 说明](https://open.oppomobile.com/documentation/page/search?keyword=ColorOS%20Watch)。页面依赖 JavaScript；本次取得公开索引的 API 和版本信息，未取得实际 SDK/UPK 工具包。不能据此宣称专有卡片可构建。

## 验证方式

```bash
cd multiplatform
python3 scripts/validate-android-widgets.py
./gradlew :shared:desktopTest
./gradlew :shared:compileAndroidMain :androidApp:assembleDebug :androidApp:testDebugUnitTest
```

Android 构建需要仓库既有的签名配置；PR CI 使用独立 compile-only fixture，不改现有签名策略。新增 Robolectric 4.17 仅用于 Android 单元测试，不进入 APK。

共享测试覆盖上下课边界、次日/跨周、空周/过期、异常 JSON。Android 测试实际执行 `RemoteViews.apply`，覆盖全部 provider 的资源、浅/深色、放大字体、旧/新 API 和剩余计数；文件读取测试覆盖重新写入、损坏、删除。这些测试不证明 OEM 桌面中心收录、真机进程内存或桌面 UI 成色。

真机验收至少包括：三种组件/五种尺寸、浅深色、字体1.0/1.5、4×6/5×6网格、横竖屏与折叠展开、同时添加多个实例、冷启动点击、账号退出、清应用数据、午夜/上下课/改时区、超过快照覆盖期、未审核原生回退。用 `adb shell dumpsys meminfo team.bjtuss.bjtuselfservice.kmp:widgetProvider` 记录厂商进程内存；截图使用脱敏课表。
