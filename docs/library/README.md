# 图书馆（opac.bjtu.edu.cn）借阅功能

## 功能

- 应用页「图书馆」磁贴、可加入底栏自定义候选
- 当前借阅 / 借阅历史两个 tab
- 当前借阅支持勾选、全选、单本续借、批量续借（均有确认弹窗）
- 首页「图书馆」卡片提醒逾期与 7 天内到期
- 不做离线缓存：每次进入/刷新都重新拉取（借阅时效与隐私）

## 登录协议（与浏览器一致）

图书馆不是普通的账号密码表单，而是 SSO OAuth2 + 服务端回调页换会话：

1. `GET https://sso.bjtu.edu.cn/o/authorize/?response_type=code&client_id=<图书馆客户端>&redirect_uri=https://opac.bjtu.edu.cn/OAuth2/bjtu/index`
   - App 登录 MIS 后 SSO 会话仍然有效，这一跳会直接 302 回图书馆回调页，不需要再输密码；
   - SSO 会话失效时，用 App 内存中已有的统一身份认证凭据提交一次（密码 SM2 加密）。
2. 回调页 `https://opac.bjtu.edu.cn/OAuth2/bjtu/index?code=...` 是服务端渲染 HTML，内含一次性的
   `data/time/enc`（每次登录现场生成，不能用旧页面的值重放）。
3. `GET https://opac.bjtu.edu.cn/OAuth2/bjtu/login?data=&time=&enc=&displayName=&userRole=3`
   返回 `{"status":true}`。**这一步还不够**：浏览器随后会跳到
   `https://opac.bjtu.edu.cn/find/sso/login/bjtu/0?data=&time=&enc=`，该页才写入 API 鉴权用的
   `SESSION` Cookie（只做到上一步时 `/find/*` 全部 401，已实测）。
4. 之后 `/find/...` 接口只靠 Cookie 鉴权；真实抓包与联调确认：不带 SPA 的动态 JWT 头也能读取借阅。

实现：`shared/src/commonMain/.../data/library/LibraryLoginProtocol.kt`。
只允许在 `opac.bjtu.edu.cn` 与 `sso.bjtu.edu.cn` 的 HTTPS 443 之间跟随跳转。

## 接口

| 功能 | 请求 |
| --- | --- |
| 用户信息 | `GET /find/userInfo/getUserInfo` |
| 当前借阅 | `POST /find/loanInfo/loanList` |
| 借阅历史 | `POST /find/loanInfo/loanHistoryList` |
| 续借 | `POST /find/lendbook/reNew`，body `{"loanIds":[...]}` |

请求头：`mappingPath`、`groupCode`、`x-lang: CHI`、`content-language: zh_CN`、`X-Requested-With: XMLHttpRequest`。
响应外壳：`{success, message, errCode, data}`；401/`errCode=401` 视为会话过期，重新握手后重试一次。

## 安全与边界

- 借阅数据只存在内存，不写 SQLite；退出登录随会话销毁。
- 续借是对学校系统的写操作：必须点击 → 确认弹窗 → 才提交，不做自动续借。
- 图书馆接口与 SSO 均使用 HTTPS；未新增明文 HTTP 例外。
- 无固定证书绑定；沿用 App 现有网络层（Ktor + 学校 UA、按主机限流）。
- 页面失败时保留上次数据并就地提示，不清空列表。

## 真实链路联调（可选，默认跳过）

凭据不从对话或代码中传入。约定在本机仓库根目录创建（已被 `.gitignore` 忽略）：

```ini
LIBRARY_TEST_USERNAME=你的学号
LIBRARY_TEST_PASSWORD=你的统一身份认证密码
```

然后只在需要时运行：

```bash
cd multiplatform
./gradlew :shared:desktopTest --tests "*LibraryLiveTest*"
```

`LibraryLiveTest` 只做登录与读取（用户信息、当前借阅、历史借阅），**不调用续借**；
日志只输出成功/失败、借阅条数、卡状态等脱敏结果，不打印用户名、密码、Cookie 或书名。

## 本机（Windows）构建准备

- JDK 21（本机 `~/.jdks/jbr-21.0.11`），`JAVA_HOME` 指向它；Android SDK 通过 `ANDROID_HOME` 或 `multiplatform/local.properties` 的 `sdk.dir` 指定。
- Android 构建要求上传 keystore 与本机签名口令（debug/release 共用）。本机测试可自行生成一个
  `~/.android/bjtu-kmp-upload.keystore`（`keytool -genkeypair`），然后在
  `~/.gradle/gradle.properties` 写入 `bjtuAndroidStorePassword` / `bjtuAndroidKeyAlias` /
  `bjtuAndroidKeyPassword`（或设置同名环境变量 `BJTU_ANDROID_STORE_PASSWORD` 等）。
  这些口令只用于本地测试签名，不要用于发布。
- 首次构建需联网下载 Gradle 与依赖（本机已缓存）。注意：Android Studio 同步时把 wrapper 改成了
  Gradle 9.8.0 并生成 `gradle/gradle-daemon-jvm.properties`（toolchain 25），这些是 IDE 生成的
  未提交改动；提交前需决定保留还是还原为仓库原来的 9.3.1。

## 验证记录（2026-10-21）

- `:shared:compileKotlinDesktop` 通过。
- `:shared:desktopTest`：973 项测试，5 项失败均为本机 macOS 专属测试（`DesktopAgentIntegrationTest`、`MacUninstallCleanerTest`）；图书馆新增 34 项测试全部通过。
- `:androidApp:compileDebugKotlin`、`:windowsApp:compileKotlinWindows`：见 `.artifacts/library-verification.md`。
- 未做：真机安装、真实账号登录/续借、iOS/macOS 构建。

## 验证清单

- `:shared:desktopTest`：解析、续借结果归一化、提醒计算、LibraryModel 状态
- `:androidApp:assembleDebug` / `:windowsApp:compileKotlinWindows`：编译
- 真机：应用页磁贴、底栏自定义、当前/历史 tab、单本续借、全选批量续借、冻结状态、首页提醒、
  断网/会话过期/空借阅
