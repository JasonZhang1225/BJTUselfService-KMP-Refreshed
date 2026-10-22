# 双更新源

设置 → 版本与项目 → 更新源：

- 自动选择（默认）：GitHub 请求最多等待 5 秒；超时、非 2xx、网络/解析异常或无可用发行版时，尝试 Gitee（最多 8 秒）。GitHub 成功时不额外请求 Gitee。
- GitHub：只访问 GitHub。
- Gitee：只访问 Gitee。

更新请求使用独立公开客户端，不发送学校会话。页面退出/协程取消会停止检查，不继续回退。检查中禁用源选择与重复检查，设置保存失败时恢复原选择并提示。

GitHub 保持既有 Releases 列表首条非 draft 逻辑（包括紧急修复 pre-release）和既有版本比较方式。Gitee 使用同一套版本比较，只识别同时具有 `<!-- bjtu-mirror-ready:v1 -->` 标记及 APK/IPA/DMG/MSI 四端稳定下载链接的发行版；忽略半上传条目。Gitee 列表重试造成顺序变化时，选择版本最高者。

选定源及已知最高发行版通过现有键值设置表持久化，不改 schema。切换源/重启后遇到低于已知最高版本的响应，手动检查提示“更新源尚未同步”，自动检查静默忽略；远端版本必须严格高于当前 App 才出现下载更新。没有跨源历史时，只能判断安装版本和当前源的可用版本，无法推断不可达 GitHub 尚未同步的新版本，因此无更新文案明确显示来源和其可用版本。

下载弹窗显示实际来源，并跳转该源当前 tag 的 Release 页面。默认版本不变（1.8.6-KMP / Build 44）；支持 Android、iOS、Windows、macOS，共享层不引入平台 API。

## 临时 Gitee 验收

按所有者授权创建 `v2.0.0-gitee-test`，仅用于阻断 GitHub 后验证回退与下载，Gitee 标为预发行版，测试后由所有者删除。标签标识测试目标，不代表安装包内部版本升级为 2.0。该固定 `-gitee-test` 后缀不写入已知最高发行版，以免删除后影响后续 1.x 正常更新；普通发行版的防回退记录照常持久化。

先安装功能分支产物（旧 App 没有双源选择），在设置选“自动选择”，阻断 `api.github.com` 后手动检查，约 5 秒后应显示 Gitee 2.0 测试条目，“前往下载”打开 Gitee。再选“Gitee”验证不等待 GitHub；选“GitHub”应检查失败。下载内容须与 Actions 原始产物哈希一致。测试不能证明 SideStore 重签/真机安装、Windows 实机运行、所有运营商或未来 Gitee 匿名策略。

回退：撤回本 PR；旧设置键会被旧代码忽略。Gitee 镜像配置及上传限制见 PR1 的 `docs/updates/gitee-mirror.md`。

## 本地验证

在 `multiplatform/` 使用 Android Studio JBR 21，桌面打包 runtime 为 Temurin 25：

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug :desktopApp:compileKotlin :windowsApp:compileKotlinWindows :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64
./gradlew :desktopApp:createDistributable
```

`--update-source-smoke [--narrow]` 为无网络、无账号、无持久化的桌面界面验证入口，覆盖源选择、加载、失败、正常更新和落后镜像提示。真实源回退和下载另行验收，不以 fixture 通过代替。
