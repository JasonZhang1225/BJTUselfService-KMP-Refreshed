package team.bjtuss.bjtuselfservice.shared.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification
import platform.AppKit.accessibilityDisplayShouldIncreaseContrast
import platform.AppKit.accessibilityDisplayShouldReduceMotion
import platform.AppKit.accessibilityDisplayShouldReduceTransparency
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

/**
 * macOS 没有 iOS 那样的动态字体档位：系统不提供「动态类型」缩放，
 * 只有辅助功能里的「增大对比度」「减弱动态效果」「降低透明度」。
 * 因此字体缩放恒为应用默认值，可访问性三项跟随系统设置并实时更新。
 *
 * 三个读取接口是 AppKit 的包级扩展（不是 NSWorkspace 的成员），必须显式 import 才能解析。
 */
@Composable
actual fun rememberPlatformFontScale(defaultFontScale: Float): Float = defaultFontScale

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun rememberPlatformIncreasedContrast(): Boolean {
    var increasedContrast by remember {
        mutableStateOf(NSWorkspace.sharedWorkspace.accessibilityDisplayShouldIncreaseContrast())
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            increasedContrast = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldIncreaseContrast()
        }
        onDispose { center.removeObserver(observer) }
    }
    return increasedContrast
}

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun rememberPlatformReduceMotion(): Boolean {
    var reduceMotion by remember {
        mutableStateOf(NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion())
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            reduceMotion = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceMotion()
        }
        onDispose { center.removeObserver(observer) }
    }
    return reduceMotion
}

@Composable
@OptIn(ExperimentalForeignApi::class)
actual fun rememberPlatformReduceTransparency(): Boolean {
    var reduceTransparency by remember {
        mutableStateOf(NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceTransparency())
    }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            name = NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) {
            reduceTransparency = NSWorkspace.sharedWorkspace.accessibilityDisplayShouldReduceTransparency()
        }
        onDispose { center.removeObserver(observer) }
    }
    return reduceTransparency
}
