import QuartzCore
import SwiftUI
import UIKit

// 共享宿主基础：Compose 宿主的无障碍与底色约定、边缘返回手势接管。
// 只放与具体导航容器无关、被多个宿主共用的约定。

// Keep this pair aligned with LightColors/DarkColors.background in shared App.kt.
let appBackgroundUIColor = UIColor { traits in
    if traits.userInterfaceStyle == .dark {
        return UIColor(red: 16.0 / 255.0, green: 18.0 / 255.0, blue: 22.0 / 255.0, alpha: 1)
    }
    return UIColor(red: 244.0 / 255.0, green: 245.0 / 255.0, blue: 249.0 / 255.0, alpha: 1)
}
let appBackgroundColor = Color(uiColor: appBackgroundUIColor)

/// 每个 Compose 宿主管线的公共约定（M10 回退壳与 M17 玻璃壳共用，行为必须一致）。
///
/// 本项目暂不开放实验性的 Compose iOS 无障碍语义树。iOS 26 的辅助功能客户端（含
/// 各类自动化查询）会在原生 push/pop 移除宿主控制器后继续查询已失效的 Compose
/// AccessibilityElement，在框架 cachedProperties 内崩溃。在 Swift 侧对整个 Compose
/// 宿主视图隐藏无障碍子树即可完全跳过该路径，视觉界面与原生导航手势不受影响。
///
/// Compose 首帧之前 UIKit 会先显示宿主底色；与页面背景保持一致可避免深色模式闪白。
///
/// 宿主只设置底色，不用计时遮罩覆盖 Compose：计数撤罩会使已滑入的新页先空白再突然显示内容。
func configureComposeHost(_ controller: UIViewController) {
    controller.view.accessibilityElementsHidden = true
    pinComposeSurfaceColor(controller.view)
}

func pinComposeSurfaceColor(_ view: UIView) {
    view.backgroundColor = appBackgroundUIColor
    // 转场合成器按 isOpaque 决定是否透出后面的白窗/黑窗。内容本身已经铺满底色，
    // 标成不透明可避免 iOS 26 玻璃推入时把后页加亮后再盖上本页。
    view.isOpaque = true
    pinMetalLayerBackground(view)
}

private func pinMetalLayerBackground(_ view: UIView) {
    let color = appBackgroundUIColor.resolvedColor(with: view.traitCollection).cgColor
    pinMetalLayers(in: view.layer, color: color)
    for child in view.subviews {
        pinMetalLayerBackground(child)
    }
}

private func pinMetalLayers(in layer: CALayer, color: CGColor) {
    if let metal = layer as? CAMetalLayer {
        metal.backgroundColor = color
    }
    for sublayer in layer.sublayers ?? [] {
        pinMetalLayers(in: sublayer, color: color)
    }
}

/// 边缘返回手势接管：Compose 的 Skia 层会吃掉左缘触摸，pop 手势必须优先于列表滚动/横滑。
/// 导航栏隐藏时 UIKit 默认会关掉 interactivePopGestureRecognizer（内部 delegate 认为
/// 没有返回按钮就不该 pop），因此栈深判定也要自己接管。
func installInteractivePopGesture(
    on navigationController: UINavigationController
) {
    navigationController.interactivePopGestureRecognizer?.isEnabled = true
    navigationController.interactivePopGestureRecognizer?.delegate =
        navigationController as? UIGestureRecognizerDelegate
}
