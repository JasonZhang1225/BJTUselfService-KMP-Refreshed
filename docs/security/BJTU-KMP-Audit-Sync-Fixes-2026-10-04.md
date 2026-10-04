# 1.8.1 audit 同步问题修复（2026-10-04）

## 用户安装反馈与原因

- 课程表在其他模块已开始同步时仍显示“等待同步”：登录完成后，自动同步协调器再次执行 MIS 会话探测；课表与作业、考试共用协调器，需等待其探测完成。底层会话请求队列也会放大这段等待。
- macOS / iOS 作业同步失败：逐跳白名单握手把 CAS `/o/authorize` 的 301 相对 Location 应用到带旧查询参数的 URLBuilder；Ktor `takeFrom` 追加参数，导致 `response_type`、`client_id`、`redirect_uri` 重复，学校 CAS 返回 HTTP 400。
- macOS 右上角两个刷新图标：非“已同步”的静止状态统一使用刷新图标，失败状态与真正刷新动作无法区分。

## 修复

- 登录成功后的自动同步明确复用刚验证的会话，直接启动模块工作。手动刷新仍先探测会话；模块返回会话失效时仍恢复登录并重试。登录未完成时仍只读取缓存。
- 解析握手重定向前清空旧查询参数，下一跳只使用 Location 的参数。原有逐跳 origin 白名单、跳数上限及禁止业务 sessionid 跨域的策略保留。
- 静止同步状态显示对勾（成功）、感叹号（失败）或信息标记（未同步）；左侧仍打开同步详情，右侧单独执行刷新。同步中仍显示进度胶囊。

## 验证

- 使用本机已有诊断账号真实复现修复前 CAS 400；修复后完成同一握手并成功读取 8 条作业。诊断仅查询，不上传或提交作业，不记录账号、密码、Cookie、OAuth 查询值或作业正文。
- 加入精确重定向 URL 回归，覆盖带 OAuth 查询的 CAS 相对 301；加入新登录不重复探测、发现失效仍能恢复的协调器回归。
- `:shared:desktopTest`：579 项，0 失败。
- `:shared:iosSimulatorArm64Test`：539 项，0 失败。
- Android Release APK、macOS DMG 构建通过；APK 签名及包版本检查通过，DMG 镜像校验与 App 严格签名验证通过。
- iOS Release 真机构建通过；元数据脚本、iPhoneOS/arm64/最低 iOS 16.0、1.8.1（22）、无签名/provisioning/PlugIns 与 IPA 全包 CRC 校验通过。IPA ZIP 使用 UTF-8 文件名并保留可执行权限。APK 全包 CRC 校验通过。

课程表仍需要等待学校真实接口的响应；本次去掉的是新登录后重复探测造成的启动等待，未取消底层会话请求的串行保护。iOS 的共享回归通过，真实作业请求证据来自 Mac，签名后 iPhone 的安装验收仍需进行。

## 交付

应用版本仍为 1.8.1（22）；新文件名包含 `audit-fix1`，与 2026-10-03 的旧包区分。三个包均来自本次 audit 应用源码。

| 文件（Downloads） | 字节数 | SHA-256 |
|---|---:|---|
| BJTUselfServiceKMP-1.8.1-audit-fix1.dmg | 120999743 | `65984d016ea211cfe99d8dd8fb14aa8454a56c912f25cdf0367e313687a03e5b` |
| BJTUSelfService-KMP-1.8.1-audit-fix1-iOS-unsigned.ipa | 41829106 | `f2fe7ad6d796e60b5eb89079d36b5d478597cec6363118f1e3bc924577b8ed9f` |
| BJTUSelfService-KMP-1.8.1-audit-fix1-arm64-v8a.apk | 88750293 | `22625c5ef64dc0b25073d55f8de6f74a25dbe317e330fdf7b4e86f57db0ccc68` |

Downloads 同时提供 `BJTUselfServiceKMP-1.8.1-audit-fix1-SHA256SUMS.txt` 和 `BJTUselfServiceKMP-1.8.1-audit-fix1-manifest.json`。
