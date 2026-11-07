# Android / HyperOS 刷新率测试流程记录

> 本文只记录刷新率相关测试的环境、操作顺序、命令和复核字段，不记录结论。测试记录不得包含账号、密码、Cookie、验证码、邮件正文或带会话参数的 URL。

## 1. 测试对象

- KMP 包：`team.bjtuss.bjtuselfservice.kmp`
- 原版 Android 包：`team.bjtuss.bjtuselfservice`
- 临时包名对照：`team.bjtuss.bjtuselfservice.kmp.probe`
- 小米平板 ADB 序列号：`6c5a737e`
- Android 模拟器 ADB 序列号：`emulator-5554`
- ADB：`C:\Users\zjg\Android\Sdk\platform-tools\adb.exe`
- JDK：`C:\Users\zjg\jdk21\jdk-21.0.8+9`
- Android SDK：`C:\Users\zjg\Android\Sdk`

每轮测试不得卸载原版包；临时 probe 包在本轮测试结束后卸载。

## 2. 设备连接和前台状态

~~~powershell
$adb = 'C:\Users\zjg\Android\Sdk\platform-tools\adb.exe'
$serial = '6c5a737e'

& $adb devices
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell getprop ro.build.version.release
& $adb -s $serial shell getprop ro.build.version.sdk
& $adb -s $serial shell dumpsys power | Select-String 'mWakefulness=|mIsPowered=|mLowPowerModeEnabled=|mInteractive='
& $adb -s $serial shell dumpsys window windows | Select-String 'mResumeActivity|mCurrentFocus|mFocusedApp'
~~~

记录设备是否为 `device`、是否解锁、是否点亮、低电量模式、充电状态、前台 Activity 和当前焦点窗口。

## 3. 系统刷新率设置读取

~~~powershell
$pairs = @(
    @('system','peak_refresh_rate'),
    @('system','min_refresh_rate'),
    @('system','refresh_rate'),
    @('system','user_refresh_rate'),
    @('system','custom_mode_switch'),
    @('system','is_smart_fps'),
    @('system','thermal_limit_refresh_rate'),
    @('system','plugin_refresh_rate'),
    @('system','screen_mode_type'),
    @('system','screen_optimize_mode'),
    @('system','power_save_mode_open'),
    @('secure','miui_refresh_rate'),
    @('secure','user_refresh_rate'),
    @('secure','refresh_rate_mode'),
    @('global','refresh_rate_mode'),
    @('global','low_power')
)

foreach ($pair in $pairs) {
    $value = (& $adb -s $serial shell settings get $pair[0] $pair[1]).Trim()
    Write-Output "$($pair[0]).$($pair[1])=$value"
}

& $adb -s $serial shell cmd display get-user-preferred-display-mode 0
& $adb -s $serial shell cmd display get-match-content-frame-rate-pref
~~~

## 4. DisplayModeDirector 和 SurfaceFlinger 读取

~~~powershell
& $adb -s $serial shell dumpsys display | Select-String 'DisplayDeviceInfo\{|mDisplayModeSpecs=|mActiveModeId=|mActiveSfDisplayMode=|mActiveRenderFrameRate=|mDesiredDisplayModeSpecs=|mForegroundAppPackageName=|mFrameRateOverrides|mPendingFrameRateOverrideUids|mIgnorePreferredRefreshRate|PRIORITY_MIUI_REFRESH_RATE' | Select-Object -First 120
& $adb -s $serial shell dumpsys SurfaceFlinger --layers | Select-String 'team.bjtuss.bjtuselfservice|Frame Rate \(Explicit\)|frameRateOverrideConfig|renderRate|displayManagerPolicy' | Select-Object -Last 160
~~~

进入应用前、进入应用后、手动恢复后分别记录 `DisplayDeviceInfo`、`mDisplayModeSpecs`、`mDesiredDisplayModeSpecs`、`mActiveModeId`、`mActiveSfDisplayMode`、`mActiveRenderFrameRate`、`mForegroundAppPackageName`、`mIgnorePreferredRefreshRate` 以及应用 layer 的 renderRate、显式帧率和 displayManagerPolicy。

## 5. HyperOS 设置页检查

~~~powershell
& $adb -s $serial shell am start -a android.settings.DISPLAY_SETTINGS
& $adb -s $serial shell am start -n com.xiaomi.misettings/.display.RefreshRate.RefreshRateActivity
& $adb -s $serial shell am start -n com.xiaomi.misettings/.display.RefreshRate.HighRefreshOptionsActivity
~~~

在平板界面记录“标准 / 高刷”、“默认（推荐） / 自定义”、自定义数值、“使用高刷新率的应用”中的 KMP 应用名及开关或跟随应用内设置文字，以及原版应用名和开关状态。

## 6. 固定 120Hz 基线

屏幕宽高从第 4 节的 `DisplayDeviceInfo` 读取。当前平板测试参数为 `2136 3200 120`。

~~~powershell
& $adb -s $serial shell settings put secure miui_refresh_rate 120
& $adb -s $serial shell settings put secure user_refresh_rate 120
& $adb -s $serial shell settings put system peak_refresh_rate 120
& $adb -s $serial shell settings put system custom_mode_switch 1
& $adb -s $serial shell settings put system is_smart_fps 0
& $adb -s $serial shell cmd display set-match-content-frame-rate-pref 0
& $adb -s $serial shell cmd display set-user-preferred-display-mode 2136 3200 120 0
Start-Sleep -Seconds 3
~~~

立即执行第 3、4 节；再等待 10 秒重复执行第 4 节。

## 7. 正常 KMP 构建、安装和启动

~~~powershell
$env:JAVA_HOME = 'C:\Users\zjg\jdk21\jdk-21.0.8+9'
$env:ANDROID_HOME = 'C:\Users\zjg\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'C:\Users\zjg\Android\Sdk'

Set-Location 'C:\Users\zjg\BJTUselfService-KMP-Refreshed\multiplatform'
.\gradlew.bat :androidApp:assembleDebug --no-daemon

$apk = 'C:\Users\zjg\BJTUselfService-KMP-Refreshed\multiplatform\androidApp\build\outputs\apk\debug\androidApp-arm64-v8a-debug.apk'
& $adb -s $serial install -r $apk
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice.kmp
& $adb -s $serial shell am start -n team.bjtuss.bjtuselfservice.kmp/.MainActivity
Start-Sleep -Seconds 5
~~~

启动前后执行第 3、4 节，并读取：

~~~powershell
& $adb -s $serial shell dumpsys package team.bjtuss.bjtuselfservice.kmp | Select-String 'versionCode|targetSdk|versionName'
& $adb -s $serial shell dumpsys window windows | Select-String -Context 3,6 'pkg = team.bjtuss.bjtuselfservice.kmp'
~~~

## 8. 原版 Android 对照

在同一组第 6 节固定基线下执行：

~~~powershell
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice
& $adb -s $serial shell am start -n team.bjtuss.bjtuselfservice/.MainActivity
Start-Sleep -Seconds 5
~~~

启动前后执行第 3、4 节，并读取原版包的 targetSdk、前台 Activity、窗口刷新率字段和 SurfaceFlinger layer。

## 9. targetSdk 34 对照

### 9.1 仅降低 targetSdk

将 `multiplatform/androidApp/build.gradle.kts` 中的 `targetSdk = 35` 临时改为 `targetSdk = 34`，执行第 7 节和第 3、4 节。

~~~powershell
& $adb -s $serial shell dumpsys package team.bjtuss.bjtuselfservice.kmp | Select-String 'versionCode|targetSdk|versionName'
~~~

### 9.2 targetSdk 34 加 ARR 属性

临时加入 `import android.os.Build`，并在 `MainActivity.onCreate` 中加入：

~~~kotlin
if (Build.VERSION.SDK_INT >= 35) {
    window.setFrameRatePowerSavingsBalanced(false)
}
~~~

执行构建、安装、启动和第 3、4 节；完成后删除 import 和代码块。

## 10. 窗口级刷新率请求对照

在 `enableEdgeToEdge()` 后临时加入：

~~~kotlin
window.attributes = window.attributes.apply {
    preferredRefreshRate = 120f
}
~~~

执行构建、安装、启动和第 3、4 节；再读取 KMP 窗口的 `preferredRefreshRate`、DisplayModeDirector 和 SurfaceFlinger 字段。完成后删除临时代码。

## 11. Manifest 应用分类对照

在 KMP 的 `<application>` 节点临时加入：

~~~xml
android:appCategory="productivity"
~~~

执行构建、安装、启动和第 3、4 节；完成后删除该属性。

## 12. 包名隔离对照

把 `applicationId` 临时改为：

~~~kotlin
applicationId = "team.bjtuss.bjtuselfservice.kmp.probe"
~~~

构建、安装并读取入口：

~~~powershell
& $adb -s $serial install -r $apk
& $adb -s $serial shell cmd package resolve-activity --brief team.bjtuss.bjtuselfservice.kmp.probe
~~~

使用解析出的完整组件启动；当前组件形式为：

~~~powershell
& $adb -s $serial shell am start -n team.bjtuss.bjtuselfservice.kmp.probe/team.bjtuss.bjtuselfservice.kmp.MainActivity
Start-Sleep -Seconds 5
~~~

启动前后执行第 3、4 节。完成后清理：

~~~powershell
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice.kmp.probe
& $adb -s $serial uninstall team.bjtuss.bjtuselfservice.kmp.probe
~~~

恢复原 applicationId，并重新执行第 7 节构建流程。

## 13. 启动日志采集

每轮日志测试前清空日志缓冲区，不将导出的日志提交到仓库：

~~~powershell
& $adb -s $serial shell logcat -c
~~~

写入第 6 节基线，启动 KMP，等待 5 秒：

~~~powershell
& $adb -s $serial shell settings put secure miui_refresh_rate 120
& $adb -s $serial shell settings put secure user_refresh_rate 120
& $adb -s $serial shell settings put system peak_refresh_rate 120
& $adb -s $serial shell cmd display set-user-preferred-display-mode 2136 3200 120 0
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice.kmp
& $adb -s $serial shell am start -n team.bjtuss.bjtuselfservice.kmp/.MainActivity | Out-Null
Start-Sleep -Seconds 5
& $adb -s $serial shell logcat -b all -d -v threadtime | Select-String -Pattern 'team\.bjtuss\.bjtuselfservice|miui_refresh|refresh.?rate|RefreshRate|DisplayModeDirector|PowerKeeper|powerkeeper|Joyose|joyose|frame.?rate|DynamicFPS|DVRR|dvrr' | Select-Object -Last 260
~~~

记录同一时间段内的 Activity 生命周期、窗口首次绘制、DisplayModeDirector 策略和投票、`RefreshRateSelector`、`MiuiRefreshRatePolicy`、`SmartDisplayPolicy`、PowerKeeper、Joyose、DynamicFPS 和 SurfaceFlinger 行。

## 14. APK 与 Manifest 对照

~~~powershell
$origRemote = ((& $adb -s $serial shell pm path team.bjtuss.bjtuselfservice) -replace '^package:','').Trim()
$kmpRemote = ((& $adb -s $serial shell pm path team.bjtuss.bjtuselfservice.kmp) -replace '^package:','').Trim()
& $adb -s $serial shell pm path team.bjtuss.bjtuselfservice
& $adb -s $serial shell pm path team.bjtuss.bjtuselfservice.kmp
& $adb -s $serial pull $origRemote "$env:TEMP\bjtu-original.apk"
& $adb -s $serial pull $kmpRemote "$env:TEMP\bjtu-kmp.apk"

$aapt = 'C:\Users\zjg\Android\Sdk\build-tools\36.0.0\aapt2.exe'
& $aapt dump badging "$env:TEMP\bjtu-original.apk" | Select-String 'package:|launchable-activity|uses-feature|uses-permission'
& $aapt dump badging "$env:TEMP\bjtu-kmp.apk" | Select-String 'package:|launchable-activity|uses-feature|uses-permission'
& $aapt dump xmltree "$env:TEMP\bjtu-original.apk" --file AndroidManifest.xml | Select-String 'uses-sdk|application|activity|theme|targetSdkVersion|appCategory|configChanges|enableOnBackInvokedCallback'
& $aapt dump xmltree "$env:TEMP\bjtu-kmp.apk" --file AndroidManifest.xml | Select-String 'uses-sdk|application|activity|theme|targetSdkVersion|appCategory|configChanges|enableOnBackInvokedCallback'
~~~

## 15. MIUI 设置与 provider 只读检查

~~~powershell
& $adb -s $serial shell dumpsys package com.xiaomi.joyose | Select-String 'packageName|versionCode|targetSdk|provider|permission|authority'
& $adb -s $serial shell dumpsys package com.xiaomi.misettings | Select-String 'packageName|versionCode|targetSdk|provider|permission|authority'
& $adb -s $serial shell content query --uri content://com.miui.powerkeeper.configure/highRefreshRateTable --projection package_name
& $adb -s $serial shell content call --uri content://com.xiaomi.Joyose.provider/game_list --method getGameList
~~~

设置 APK 拉到临时目录后离线反编译，搜索 `HighRefreshOptionsActivity`、刷新率 Fragment/适配器、`highRefreshRateTable`、`getGameList`、`package_name`、`follow_apps_settings`、`layout_follow_app_item` 及 provider 增删调用。临时反编译目录不纳入仓库。

## 16. 模拟器对照流程

~~~powershell
$emulator = 'emulator-5554'
& $adb -s $emulator shell wm size
& $adb -s $emulator shell wm density
& $adb -s $emulator shell dumpsys display | Select-String 'DisplayDeviceInfo\{|supportedRefreshRates|mActiveModeId=|mActiveRenderFrameRate='

$env:BJTU_ANDROID_ABIS = 'arm64-v8a,x86_64'
Set-Location 'C:\Users\zjg\BJTUselfService-KMP-Refreshed\multiplatform'
.\gradlew.bat :androidApp:assembleDebug --no-daemon
& $adb -s $emulator install -r 'C:\Users\zjg\BJTUselfService-KMP-Refreshed\multiplatform\androidApp\build\outputs\apk\debug\androidApp-x86_64-debug.apk'
& $adb -s $emulator shell am force-stop team.bjtuss.bjtuselfservice.kmp
& $adb -s $emulator shell am start -n team.bjtuss.bjtuselfservice.kmp/.MainActivity
Start-Sleep -Seconds 5
~~~

模拟器启动前后执行第 2、3、4 节，单独记录模拟器与平板的模式和窗口数据。

## 17. 测试结束清理与状态固定

确认源码中不存在以下临时内容：

- `targetSdk = 34`
- `setFrameRatePowerSavingsBalanced(false)`
- `preferredRefreshRate = 120f`
- `android:appCategory="productivity"`
- `applicationId = "team.bjtuss.bjtuselfservice.kmp.probe"`

正式 KMP 配置恢复为：

~~~kotlin
applicationId = "team.bjtuss.bjtuselfservice.kmp"
targetSdk = 35
~~~

平板清理临时包并固定 120Hz：

~~~powershell
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice.kmp.probe
& $adb -s $serial uninstall team.bjtuss.bjtuselfservice.kmp.probe
& $adb -s $serial shell settings put secure miui_refresh_rate 120
& $adb -s $serial shell settings put secure user_refresh_rate 120
& $adb -s $serial shell settings put system peak_refresh_rate 120
& $adb -s $serial shell settings put system custom_mode_switch 1
& $adb -s $serial shell settings put system is_smart_fps 0
& $adb -s $serial shell cmd display set-match-content-frame-rate-pref 0
& $adb -s $serial shell cmd display set-user-preferred-display-mode 2136 3200 120 0
Start-Sleep -Seconds 3
~~~

若要让 KMP 保持前台后再固定一次：

~~~powershell
& $adb -s $serial shell am force-stop team.bjtuss.bjtuselfservice.kmp
& $adb -s $serial shell am start -n team.bjtuss.bjtuselfservice.kmp/.MainActivity
Start-Sleep -Seconds 5
& $adb -s $serial shell settings put secure miui_refresh_rate 120
& $adb -s $serial shell settings put secure user_refresh_rate 120
& $adb -s $serial shell settings put system peak_refresh_rate 120
Start-Sleep -Seconds 3
~~~

最后执行第 3、4 节，记录设置值、活动模式、前台包名和 targetSdk。

## 18. 仓库检查

~~~powershell
Set-Location 'C:\Users\zjg\BJTUselfService-KMP-Refreshed'
git diff --check
git status --short
git diff --ignore-space-at-eol -- docs/migration/android-hyperos-refresh-rate-test-procedure.md
~~~

本文不保存 APK、截图、logcat 导出、反编译产物或账号相关数据。

## 19. 2026-08-30 小米平板实测结论

设备：`25091RP04C`（piano），Android 16 / HyperOS 3.0，序列号 `6c5a737e`。

- 原版 `team.bjtuss.bjtuselfservice`（targetSdk 34）前台：`mActiveRenderFrameRate=120`，`PRIORITY_MIUI_REFRESH_RATE` 上限 120。
- KMP 旧包前台：同一套系统 120Hz 设置下被锁到 60。`dumpsys display` 为 `SWITCHING_TYPE_NONE`、`mIgnorePreferredRefreshRate=true`、`mVotesByDisplay[-1].PRIORITY_MIUI_REFRESH_RATE max=60`。应用自己的 `preferredRefreshRate` / `preferredDisplayModeId` 会被忽略。
- 系统「使用高刷新率的应用」把 KMP 显示成「跟随应用内设置」，是因为应用声明了窗口刷新率，或启动时创建了 WebView/Chromium。PowerKeeper 随后按 60Hz 投票。
- 把 `preferredRefreshRate=120` 写进窗口（第 10 节）不但不能抬到 120，还会强化「跟随应用内设置」。
- 有效做法：不要在窗口上声明刷新率；关掉 Android 15 ARR 省电降帧；不要在 `MainActivity.onCreate` 预热 WebView。修复后 KMP 前台 `mActiveRenderFrameRate=120.00001`，与原版切换往返后仍保持 120。
