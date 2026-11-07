# Android v1.7.0 脱敏视觉基线

本目录记录官方 `v1.7.0@419313d` 在 Pixel 10 Pro XL API 37.1 模拟器上的真实页面布局。图片仅用于迁移时比较信息层级、导航、卡片、网格、弹层和响应式表现，不用于保存业务数据。

## 脱敏规则

- 原始截图只在生成过程的本机临时文件中存在，生成后立即删除。
- Android 可访问性树中所有非空文字区域统一以灰色矩形覆盖，包括姓名、课程、成绩、余额、邮件、日期和状态。
- WebView 内部网页不保证出现在 Android 可访问性树中。邮箱页因此不保留网页内容，`android-v1.7.0-email-redacted.png` 被整页覆盖，只作为“不得使用自动文字框脱敏 WebView”的安全标记。
- Dialog 可能只暴露弹层语义、隐藏背景语义。成绩详情基线额外覆盖弹层之外的全部背景；校园卡和校园网弹层图整页覆盖，只保留交互日志中的白名单标签证据。
- 文件名包含 `redacted` 才可保留；目录内不得出现 `raw`、账号、Cookie、验证码或原始 UI XML。

## 页面索引

| 文件 | 真实验证内容 |
| --- | --- |
| `android-v1.7.0-home-redacted.png` | 首页三张状态卡、日历/DDL 信息流、三项底部导航 |
| `android-v1.7.0-apps-redacted.png` | 七个应用入口的两列大卡片网格 |
| `android-v1.7.0-settings-top-redacted.png` | 用户、更新、清数据、GitHub、主题、动态色、背景入口 |
| `android-v1.7.0-settings-bottom-redacted.png` | 四项自动同步、更新提示、退出账号 |
| `android-v1.7.0-grade-redacted.png` | 汇总、筛选、排序、成绩列表 |
| `android-v1.7.0-grade-selection-redacted.png` | 自选课程模式与逐项复选框 |
| `android-v1.7.0-grade-detail-redacted.png` | 成绩详情弹层轮廓；背景额外整区覆盖 |
| `android-v1.7.0-course-schedule-redacted.png` | 七列周课表、节次行和顶部控制 |
| `android-v1.7.0-exams-redacted.png` | 考试卡片列表及信息层级 |
| `android-v1.7.0-homework-redacted.png` | 作业筛选、状态、详情字段、上传/下载动作布局 |
| `android-v1.7.0-courseware-redacted.png` | 课程资源列表、展开、下载、日历动作 |
| `android-v1.7.0-courseware-expanded-redacted.png` | 课程展开后的文件层级 |
| `android-v1.7.0-classroom-redacted.png` | 教学楼列表 |
| `android-v1.7.0-classroom-details-redacted.png` | 教室人数、有效期、排序和七日占用指示 |
| `android-v1.7.0-other-functions-redacted.png` | 校历和中英文成绩单下载 |
| `android-v1.7.0-ecard-dialog-redacted.png` | 整页安全覆盖；确认弹层由“校园卡充值/打开应用/取消”白名单标签证明 |
| `android-v1.7.0-net-dialog-redacted.png` | 整页安全覆盖；确认弹层由“校园网续费/分享至微信/取消”白名单标签证明 |

`android-v1.7.0-classroom-schedule-redacted.png` 未出现预期安排弹层，只能作为点击后仍停留在教室列表的失败证据，不能支持“安排弹层已通过”的结论。
