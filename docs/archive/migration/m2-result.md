# M2 共享领域层结果

> 完成时间：2026-07-30  
> 行为基线：`v1.7.0@419313d`  
> 实现范围：仅 `multiplatform/`，冻结 Android 工程未改动

## 结果

M2 已把第一批不依赖 UI、Room、Android `Context` 或 JVM API 的领域模型与规则放入 `shared/commonMain`：

- 成绩：模型、学期筛选、自选课程计算范围、成绩排序、学分加权平均。
- 成绩选中恢复：用课程稳定字段、学期、出现次序及最近成绩/学分恢复选择，不依赖 Room ID。
- 课程表：课程模型、单周/区间周次解析、按教学周过滤。
- 作业：作业模型、学校时间格式解析、课程/过期筛选、截止时间排序、48 小时提醒计数。
- 考试：不含 Room 注解的考试安排模型。

作业日期规则使用 `kotlinx-datetime 0.8.0`，当前时间和时区由调用层传入，单元测试不读取真实系统时钟。

## 与 Android 1.7.0 的对照

实现前逐条读取隔离源码中的以下文件，并确认当前冻结分支的对应成绩规则与 `419313d` 无差异：

- `GradeScreen.kt`
- `GradeSelectionUtils.kt`
- `GradeEntity.kt` / `GradeSelectionRecord.kt`
- `CourseEntity.kt` / `CourseScheduleScreen.kt`
- `HomeworkEntity.kt` / `HomeworkScreen.kt`
- `ExamScheduleEntity.kt`

保留的关键行为包括：

1. 成绩字符串取逗号后的数值，只有成绩和学分都可解析时才计入加权平均。
2. 自选课程计算使用全部成绩中的选中 ID，不受当前学期筛选隐藏影响。
3. 无效成绩排序值为 `-1`；升序置前、降序置后。
4. 成绩选择在数据库 ID 改变、成绩或学分更新后仍可恢复；不同学期保持独立。
5. 课程周次任意一段解析失败时返回空列表；周次 `0` 表示显示全部。
6. 作业截止时间无法解析时不过期筛选仍保留，排序时置于有效时间之后。

## 测试与构建

执行：

```bash
./gradlew \
  :shared:desktopTest \
  :shared:compileTestKotlinIosSimulatorArm64 \
  :androidApp:assembleDebug \
  :shared:linkDebugFrameworkIosSimulatorArm64 \
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 49s`，83 个任务中 80 个执行、3 个已是最新状态。

Desktop 共执行 19 个测试，0 skipped、0 failures、0 errors：

| 测试组 | 数量 |
| --- | ---: |
| 既有共享落地页 | 2 |
| 成绩规则 | 5 |
| 成绩选择恢复 | 6 |
| 课程周次 | 2 |
| 作业日期与筛选 | 4 |

iOS 的 `commonTest` 已通过 `compileTestKotlinIosSimulatorArm64` 编译；本轮没有再次运行此前会挂起的 iOS test runner，因此不能表述为 iOS 测试执行通过。Android debug APK 和 iOS Simulator arm64 framework 均成功重新构建。

## 冻结边界

交付前再次计算受保护路径聚合 SHA-256：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

与 M1 前、M1 后完全一致。M2 没有新增、覆盖或格式化冻结 Android 文件。

## 下一步边界

M3 将建立登录状态、HTTP/Cookie 抽象和脱敏解析 fixture。M2 没有发起真实网络请求、读取凭据、提交 CAPTCHA、迁移数据库或实现正式功能 UI。
