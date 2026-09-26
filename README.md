# 🚄 交大自由行 (BJTU Self Service)

> 北京交通大学校园服务客户端 —— 让校园生活触手可及  
> 本仓库为 **Kotlin Multiplatform 多端刷新版**（Android / iOS / macOS / Windows），在原安卓项目功能基线 `v1.7.0` 上迁移与增强。

[![KMP Release](https://img.shields.io/github/v/release/JasonZhang1225/BJTUselfService-KMP-Refreshed?include_prereleases&style=flat-square&label=KMP%20版本)](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases)
[![Upstream](https://img.shields.io/github/v/release/HFDLYS/BJTUselfService?style=flat-square&label=原作者安卓)](https://github.com/HFDLYS/BJTUselfService/releases/latest)
[![Android](https://img.shields.io/badge/Android-9.0%2B-brightgreen?style=flat-square&logo=android)](https://developer.android.com)
[![iOS](https://img.shields.io/badge/iOS-侧载/开发构建-000000?style=flat-square&logo=apple)](https://developer.apple.com)
[![macOS](https://img.shields.io/badge/macOS-12%2B%20Apple%20Silicon-000000?style=flat-square&logo=apple)](https://developer.apple.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform%20%2B%20Compose-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/HFDLYS/BJTUselfService?style=flat-square)](LICENSE)

## 📖 项目简介

**交大自由行** 是一款专为北京交通大学师生打造的校园服务应用。通过自动登录 MIS 系统，将成绩查询、课程表、考试安排、作业管理、邮件查看等常用校园功能整合到一个简洁直观的界面中。

所有数据解析（包括验证码识别）均在**本地完成**，无需上传至第三方服务器，充分保障用户隐私安全。

本 fork（[BJTUselfService-KMP-Refreshed](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed)）在原作者 [HFDLYS/BJTUselfService](https://github.com/HFDLYS/BJTUselfService) 安卓版基础上，用 **KMP + Compose Multiplatform** 做多端共享实现；根目录冻结原 Android 工程，**新实现在 `multiplatform/`**。当前正式版本号 **1.7.6-KMP**。

相对原版新增/增强（节选）：
- **教室占用查询**（教务 `room_view`，原 1.7.0 安卓无）
- **成绩按课程性质筛选**（必修 / 限选 / 任选 / 体育）
- **物理在线**（CAS/Moodle 会话、课程与作业安排、提交状态、批改信息和按账号隔离缓存）
- **邮箱原生前端**（宽屏三栏布局、紧凑端原生详情、文件夹切换、分页和 HTML 表格）
- **Windows 桌面端**（x64 安装包、触摸滚动兼容和桌面端自适应布局）
- 多端统一壳层：「更多」收纳、顶栏同步胶囊、平板/桌面侧栏分屏与比例分栏

## ✨ 功能特性

### 🔐 智能登录
- 免验证码自动登录 MIS 系统
- Android 本地 ONNX / Apple Core ML 验证码识别，无需第三方服务参与
- 登录状态持久化，打开即用

### 📚 学业管理
- **成绩查询** — 按学年筛选、排序，查看成绩详情，自动计算 GPA/均分
- **课程表** — 直观的课程表预览界面，支持本科生与研究生
- **考试日程** — 考试安排一览，变动自动提醒
- **作业管理** — 查看、筛选、排序作业，支持作业上传与下载
- **物理在线** — 查看物理在线课程、作业安排、提交状态、批改信息和评语；校园网不可达时显示按账号隔离的本地缓存

### 📬 信息服务
- **校内邮箱** — 复用 MIS 会话查看文件夹、邮件列表和详情，支持 HTML 表格与写信/回复
- **校园卡余额** — 实时查看一卡通余额
- **校园网余额** — 网络使用情况一目了然

### 📅 日历与提醒
- 日历视图整合作业截止日期与考试时间
- 邮箱订阅功能，自动抓取智慧课程平台作业/课程报告/实验
- 剩余时间不足阈值时自动发送邮件提醒

### 🏫 校园工具
- **教室人数估计** — 查看教室人数侦测结果（第三方接口）
- **教室占用查询** — 按教学楼 / 教学周查看排课与占用（KMP 新增）
- **校历** — 打开公众号文章查看学校最新校历
- **成绩单下载** — 支持中英文成绩单快捷下载
- **应用内更新** — 原安卓版启动时自动检测新版本；KMP 正式分发后以各平台商店或本仓库 Release 为准

## 🏗️ 技术架构

**原安卓工程（冻结）** 仍为 Jetpack Compose + Hilt + Room + OkHttp/Jsoup + PyTorch 验证码。

**KMP 刷新版（`multiplatform/`）** 共享业务与大部分 UI：

```
┌─────────────────────────────────────────────┐
│              UI（Compose Multiplatform）      │
│  Android / iOS / macOS / Windows 共享 Screen + 壳层 │
├─────────────────────────────────────────────┤
│           shared 领域 / 仓库 / 登录协议        │
│  SQLDelight 缓存 · 平台 Keystore/Keychain     │
│  Ktor + 各平台引擎 · HTML 解析                 │
├─────────────────────────────────────────────┤
│  验证码：Android TorchScript / Apple Core ML  │
└─────────────────────────────────────────────┘
```

### 主要依赖（KMP）

| 类别 | 技术 |
|------|------|
| UI | Compose Multiplatform + Material 3 |
| 网络 | Ktor |
| 本地缓存 | SQLDelight |
| 凭据 | Android Keystore / Apple Keychain |
| 验证码 | Android TorchScript、Apple Core ML |
| 宿主 | androidApp · iosApp · desktopApp · windowsApp |

## 🚀 快速开始

### 环境要求

- JDK 17+（推荐 Android Studio JBR 21 或本机 Temurin）
- Android SDK（KMP `androidApp` 当前 compileSdk 见 `multiplatform/`）
- Xcode（构建 iOS / 相关原生辅助）
- Apple Silicon macOS（Desktop 当前 arm64 自包含包）

### 构建步骤（KMP）

```bash
# 1. 克隆本 fork
git clone https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed.git
cd BJTUselfService-KMP-Refreshed

# 2. 进入 multiplatform 工程
cd multiplatform
chmod +x gradlew

# 3. Android debug
./gradlew :androidApp:assembleDebug

# 4. macOS DMG（Apple Silicon）
./gradlew :desktopApp:packageDmg

# iOS 请用 Xcode 打开 multiplatform/iosApp，按开发证书签名安装；
# 或本地打未签名 IPA 后侧载。
```

iOS 未签名 IPA 的自签与安装请参考 [iOS 自签与安装指南](docs/iOS-sign-guide.md)。

原作者纯安卓工程仍在仓库根目录，可对照构建：

```bash
./gradlew :app:assembleDebug
```

### 仓库与发布

| 仓库 | 说明 |
|------|------|
| [HFDLYS/BJTUselfService](https://github.com/HFDLYS/BJTUselfService) | 原作者安卓版与正式 Release |
| [JasonZhang1225/BJTUselfService-KMP-Refreshed](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed) | 本 KMP 多端 fork 与正式 Release |

本 fork 的 `1.7.6-KMP` 正式包由 GitHub Actions 远端构建并上传到 [GitHub Release](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.6-KMP)。

## 📱 支持平台

- **Android**：minSdk 28+，KMP 包名 `team.bjtuss.bjtuselfservice.kmp`（与原版包名不同）
- **iOS**：开发/侧载构建（未签名 IPA 需自行重签名，参考 [iOS 自签与安装指南](docs/iOS-sign-guide.md)）
- **macOS**：Apple Silicon 自包含 `.app` / `.dmg`（开发 ad-hoc 签名，未公证）
- **Windows**：x64 系统级 `.msi` 安装包，支持触摸滚动和同数值版本覆盖升级

## 🔒 隐私与安全

- ✅ 验证码通过本地 ONNX/Core ML 模型识别，**不上传任何数据到第三方服务器**
- ✅ 账号密码仅存储在本地设备
- ✅ Android Keystore、Apple Keychain、Windows DPAPI 保存登录凭据
- ✅ macOS/Windows 离线缓存字段使用系统密钥支持的 AES-256-GCM 加密

### 🗑️ 卸载与本地数据清理

- **Android / iOS**：卸载即清除应用私有数据。iOS 重装后因安装内“记住密码”标记缺失，会删除残留 Keychain 凭据而不会静默恢复；缓存数据库已设置不随 iCloud/iTunes 备份上云。
- **Windows**：MSI 卸载会自动删除 `%LOCALAPPDATA%\BJTUselfServiceKMP`（离线缓存）和 `HKCU\Software\JavaSoft\Prefs\team\bjtuss\bjtuselfservice`（DPAPI 加密凭据）；升级安装不会清数据。
- **macOS**：拖拽删除 `.app` 不会清除用户数据。卸载前请在应用内「设置 → 本地数据与会话 → 清除全部本地数据」执行全量清理；如已删除应用，可手动删除：
  - `~/Library/Application Support/BJTUselfServiceKMP/`（AES-256-GCM 加密离线缓存）
  - 「钥匙串访问」中服务名为 `team.bjtuss.bjtuselfservice.kmp.credentials` 的条目（登录凭据）
  - 「钥匙串访问」中服务名为 `team.bjtuss.bjtuselfservice.kmp.cache-key` 的条目（缓存随机密钥，本身不含个人数据）
  - `~/Library/Preferences/` 下 `team/bjtuss/bjtuselfservice` 相关的偏好文件（记住密码标记）

安全审计与修复记录见 [docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md](docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md)。

## 🤝 贡献者

感谢以下所有为本项目做出贡献的朋友们：
- [optsimauth](https://github.com/optsimauth): 
  - 重构了整个项目的架构，优化了代码结构
  - 以及后续若干跟进
- [guh0613](https://github.com/guh0613)
  - 提供自动构建与发布
- [carolyn-sun](https://github.com/carolyn-sun)
  - 优化了工作流配置
- [NAPHthalene130](https://github.com/NAPHthalene130)
  - 修复若干 bug
- [B-Silva20](https://github.com/B-Silva20)
  - 提供了成绩自选课程计算
- [wangxiaobo1747](https://github.com/wangxiaobo1747) 
  - 提供了自定义壁纸和桌面课程表小组件

- [JasonZhang1225](https://github.com/JasonZhang1225)
  - KMP 多端迁移（Android / iOS / macOS / Windows）、物理在线与邮箱前端重写，以及后续壳层、教室占用等增强
