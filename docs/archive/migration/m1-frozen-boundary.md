# M1 冻结 Android 工程边界

> 记录时间：2026-07-30  
> 用途：证明 M1 只新增 `multiplatform/`，没有改变现有 Android 工程或覆盖用户当前未提交修改。

## 受保护路径

```text
app/
build.gradle.kts
settings.gradle.kts
gradle/
gradle.properties
gradlew
gradlew.bat
```

当前边界包含 154 个 Git 跟踪文件。开始 M1 前已有 6 条用户修改，必须原样保留：

```text
 M app/build.gradle.kts
 M app/src/main/java/team/bjtuss/bjtuselfservice/MainActivity.kt
 M app/src/main/java/team/bjtuss/bjtuselfservice/screen/SettingScreen.kt
 M gradle.properties
 M gradlew
 M settings.gradle.kts
```

当前聚合 SHA-256：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

该摘要同时包含：

- 受保护路径的 `git status --porcelain=v1 --untracked-files=all`。
- 按路径排序后的全部 Git 跟踪文件内容 SHA-256。

因此，现有文件内容、已有修改状态或受保护路径中新出现的未跟踪文件发生变化时，摘要都会改变。忽略的 `build/`、`.gradle/` 等构建产物不进入摘要。

## 复核命令

在仓库根目录运行：

```bash
tmp=$(mktemp /private/tmp/bjtu-frozen.XXXXXX)
paths=(app build.gradle.kts settings.gradle.kts gradle gradle.properties gradlew gradlew.bat)

git status --porcelain=v1 --untracked-files=all -- $paths > "$tmp"
git ls-files -z -- $paths \
  | LC_ALL=C sort -z \
  | while IFS= read -r -d '' file; do
      shasum -a 256 "$file"
    done >> "$tmp"

shasum -a 256 "$tmp"
rm -f "$tmp"
```

M1 开始前、三端构建后和交付前各复核一次。结果必须仍为上述摘要；如果不同，先定位差异，不通过重置或覆盖用户文件来“修复”。

## M1 交付复核

2026-07-30 在三端构建、安装和运行完成后，使用上面的同等字节流计算再次得到：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

受保护边界仍为 154 个跟踪文件和原有 6 条用户修改，没有因 M1 新增、覆盖或重置任何冻结文件。

## M2 交付复核

2026-07-30 在共享领域模型、19 个 Desktop 测试、iOS test 编译、Android APK 与 iOS framework 构建完成后，聚合 SHA-256 仍为：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

受保护边界仍未因 M2 发生变化；详细结果见 `m2-result.md`。

## M3 协议阶段复核

2026-07-30 在 Ktor 三平台引擎、共享登录协议、29 个 Desktop 测试、iOS test 编译和 Android APK 构建后，聚合 SHA-256 仍为：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

M3 当前所有源码变更仍限定在 `multiplatform/`；详见 `m3-login-protocol.md`。

## M3 真实登录交付复核

2026-07-30 在 KMP iOS 与 Android 完成真实 MIS/AA 登录、35 个 Desktop 测试和最终两端构建后，聚合 SHA-256 仍为：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

受保护 Android 根工程仍保持原有 6 条用户修改；M3 实现、测试与文档均位于新工程或迁移记录中。

## M5 成绩切片构建复核

2026-07-30 在成绩 Repository、共享 UI、57 个 Desktop 测试、Android debug/release、iOS 两架构、Xcode Simulator 和 macOS distributable 构建后，聚合 SHA-256 仍为：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

M5 实现仍全部位于 `multiplatform/`；冻结 Android 根工程保持原有 6 条用户修改。
