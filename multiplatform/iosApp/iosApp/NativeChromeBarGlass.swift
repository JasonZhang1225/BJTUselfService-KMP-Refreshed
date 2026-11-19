import UIKit

// 原生栏的视觉层：滚动边缘液态玻璃、居中标题，以及横/竖栏方向判定。

/// Scroll-edge Liquid Glass behind the native title, above Compose content.
///
/// Why an overlay instead of UINavigationBarAppearance.backgroundEffect (verified live):
/// manually built appearances ignore configureWith* on iOS 27, and
/// sharesBackground=false suppresses _UIBarBackground entirely. The overlay uses the
/// same Regular glass as the native sheets (see BJTUInstallNativeSheetMaterial).
/// Visibility follows the actual Compose scroll state. Duo's vertical system bar has no
/// scroll-edge effect by default, so this material is only used with horizontal bars.
final class NativeNavigationBarGlassView: UIVisualEffectView {
    private var scrollProgress: CGFloat = 0
    var hasVisibleMaterial: Bool { scrollProgress > 0 }

    init() {
        super.init(effect: Self.barGlassEffect())
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleBottomMargin]
        // Restore 5ced798's continuous fade. Later effect-view masks changed
        // the material's reveal semantics and failed the user's device check.
        alpha = 0
        isHidden = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setProgress(_ progress: CGFloat) {
        scrollProgress = min(max(progress, 0), 1)
        // One continuous scroll value drives the whole native glass surface,
        // exactly as setTopGlassAlpha did before the 1.8.1 mask changes.
        alpha = scrollProgress
        isHidden = !hasVisibleMaterial
    }

    private static func barGlassEffect() -> UIVisualEffect {
        if #available(iOS 26.0, *) {
            let glass = UIGlassEffect(style: .regular)
            glass.isInteractive = true
            // Pull the glass tone toward the page background so the bar doesn't read as
            // a separate bright band over dark content (or vice versa in light mode).
            // Explicit provider (not withAlphaComponent) so it keeps following traits.
            glass.tintColor = UIColor { traits in
                appBackgroundUIColor.resolvedColor(with: traits).withAlphaComponent(0.4)
            }
            return glass
        } else {
            return UIBlurEffect(style: .systemMaterial)
        }
    }
}

/// One native UILabel is overlaid on the navigation bar's own coordinate space.
/// `UINavigationItem.title` deliberately avoids this because UIKit shifts it to
/// avoid a long trailing action group; the app needs a title that stays centered
/// while those actions change asynchronously.
final class NativeCenteredNavigationTitle: UILabel {
    override init(frame: CGRect) {
        super.init(frame: frame)
        font = UIFontMetrics(forTextStyle: .title3).scaledFont(
            for: UIFont.systemFont(ofSize: 21, weight: .semibold),
        )
        adjustsFontForContentSizeCategory = true
        textAlignment = .center
        numberOfLines = 1
        lineBreakMode = .byTruncatingTail
        textColor = .label
        isUserInteractionEnabled = false
        accessibilityTraits = .header
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

func nativeBarIsVertical(in traits: UITraitCollection) -> Bool {
    if #available(iOS 27.1, *) { return traits.verticalBarEdge != .unspecified }
    return false
}
