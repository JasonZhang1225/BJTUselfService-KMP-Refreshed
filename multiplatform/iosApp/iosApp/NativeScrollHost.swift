import BJTUShared
import UIKit

// 原生滚动宿主：UIKit 负责手势、惯性与栏材质，Compose 只消费逐帧滚动增量。

/// One physical vertical scroller. Compose's own vertical gesture/physics is disabled.
private final class NativePageScrollView: UIScrollView, UIGestureRecognizerDelegate {
    override func touchesShouldCancel(in view: UIView) -> Bool { true }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        // A touch that stops UIKit inertia belongs to the scroller, not a card
        // in the fixed Compose canvas underneath it.
        return isDecelerating && hit != nil ? self : hit
    }

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === panGestureRecognizer else { return true }
        let velocity = panGestureRecognizer.velocity(in: self)
        return abs(velocity.y) >= abs(velocity.x)
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // Compose clicks must be cancelled when UIKit claims a vertical drag.
        // Horizontal paging still wins when our vertical pan declines to begin.
        otherGestureRecognizer is UIScreenEdgePanGestureRecognizer
    }
}

/// UIKit scrolls and animates chrome; the fixed-size Skia viewport renders consumed deltas.
final class SafeAreaComposeHost: UIViewController, UIScrollViewDelegate {
    private let content: UIViewController
    private let nativeHostState: NativeHostState
    private var contentConstraints: [NSLayoutConstraint] = []
    private var contentLayoutVertical: Bool?
    private var nativeScrollView: NativePageScrollView?
    private var scrollController: NativeScrollController?
    private var renderedOffset: CGFloat = 0
    private var nativeRange: CGFloat = 0
    private var updatingRange = false
    private var aligningToStart = false
    /// Glass is driven by the larger of Compose's scroll progress and the UIKit
    /// rubber-band displacement. A page that fits the viewport never scrolls in
    /// Compose, yet pulling it up still slides content under the bar.
    private var composeGlassProgress: CGFloat = 0
    private var overscrollGlassProgress: CGFloat = 0
    private var pushedGlassProgress: CGFloat = -1
    var hasNativeScroller: Bool { scrollController != nil }

    func setComposeGlassProgress(_ progress: CGFloat) {
        composeGlassProgress = progress
        pushTopGlassProgress()
    }

    private func pushTopGlassProgress() {
        let progress = max(composeGlassProgress, overscrollGlassProgress)
        guard progress != pushedGlassProgress else { return }
        pushedGlassProgress = progress
        (navigationController as? TabRootNavigationController)?.setTopGlassProgress(progress, for: self)
    }

    private func updateOverscrollGlass(_ scrollView: UIScrollView) {
        // Same 52pt ramp as Compose's topBarTransitionHeight.
        let band = max(0, scrollView.contentOffset.y - renderedOffset)
        let progress = hasNativeScroller ? min(1, band / 52) : 0
        let reachesEndpoint = progress == 0 || progress == 1
        guard progress != overscrollGlassProgress,
              reachesEndpoint || abs(progress - overscrollGlassProgress) > 0.005 else { return }
        overscrollGlassProgress = progress
        pushTopGlassProgress()
    }

    init(content: UIViewController, nativeHostState: NativeHostState) {
        self.content = content
        self.nativeHostState = nativeHostState
        super.init(nibName: nil, bundle: nil)
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = appBackgroundUIColor
        addChild(content)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        // Establish the final viewport before Compose's first action report. Never
        // reparent the Metal surface or resize it during a navigation transition.
        let scroller = NativePageScrollView(frame: view.bounds)
        scroller.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroller.contentInsetAdjustmentBehavior = .never
        scroller.showsVerticalScrollIndicator = false
        scroller.isDirectionalLockEnabled = true
        scroller.delaysContentTouches = true
        scroller.canCancelContentTouches = true
        scroller.panGestureRecognizer.cancelsTouchesInView = true
        scroller.alwaysBounceVertical = true
        scroller.isScrollEnabled = false
        scroller.delegate = self
        scroller.panGestureRecognizer.delegate = scroller
        scroller.backgroundColor = appBackgroundUIColor
        if #available(iOS 26.0, *) { scroller.topEdgeEffect.isHidden = true }
        nativeScrollView = scroller
        view.addSubview(scroller)
        scroller.addSubview(content.view)
        content.didMove(toParent: self)
        updateBarLayout()
    }

    func setScrollController(_ controller: NativeScrollController?) {
        loadViewIfNeeded()
        let wasEnabled = hasNativeScroller
        // An exact false edge transition represents a real Compose action such as
        // Today scrolling to the first item. A stale initial false snapshot does not.
        let returnedToTop = scrollController?.canScrollBackward == true &&
            controller?.canScrollBackward == false && renderedOffset > 0.5
        if controller == nil { scrollController?.setOverscrollTranslation(translationPx: 0) }
        scrollController = controller
        if wasEnabled != hasNativeScroller {
            nativeScrollView?.isScrollEnabled = hasNativeScroller
            setContentScrollView(hasNativeScroller ? nativeScrollView : nil, for: .top)
            if !hasNativeScroller, let scroller = nativeScrollView {
                // Loading/empty content can remove the consumer. Drop its old range
                // and rubber-band displacement without changing the viewport.
                updatingRange = true
                renderedOffset = 0
                nativeRange = 0
                scroller.setContentOffset(.zero, animated: false)
                scroller.contentSize = scroller.bounds.size
                content.view.transform = .identity
                updatingRange = false
                overscrollGlassProgress = 0
                pushTopGlassProgress()
            }
            if let navigationController = navigationController as? TabRootNavigationController {
                navigationController.configureScrollMinimization(for: self, enabled: hasNativeScroller)
            }
        }
        if returnedToTop {
            renderedOffset = 0
            nativeScrollView?.setContentOffset(.zero, animated: false)
            controller?.setOverscrollTranslation(translationPx: 0)
        }
        updateNativeRange()
        alignNativeOriginIfNeeded()
        updateTopEdgeEffect()
    }

    private func updateTopEdgeEffect() {
        // Compose reserves the title clearance inside its content; UIKit's
        // scroll view starts behind the bar even when the page is at its top.
        // Gate only the top edge, leaving the Duo sidebar and its native
        // minimization under UIKit's control. Negative rubber-band offsets
        // must remain clear as well.
        if #available(iOS 26.0, *), let scroller = nativeScrollView {
            // Content is under the bar whenever UIKit's offset is positive, including an
            // upward rubber band on a page too short to scroll in Compose.
            scroller.topEdgeEffect.isHidden = !hasNativeScroller || scroller.contentOffset.y <= 0.5
        }
    }

    private func alignNativeOriginIfNeeded() {
        guard let controller = scrollController, let scroller = nativeScrollView else { return }
        if !controller.canScrollBackward || renderedOffset > 0.5 { aligningToStart = false }
        guard !aligningToStart, controller.canScrollBackward,
              renderedOffset <= 0.5, abs(scroller.contentOffset.y) <= 0.5,
              !scroller.isTracking, !scroller.isDecelerating else { return }
        // A resized LazyColumn can preserve an old physical anchor while UIKit is
        // at zero. Fix only this idle origin mismatch, never a live native gesture.
        aligningToStart = true
        controller.alignToStart()
        controller.setOverscrollTranslation(translationPx: 0)
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        updateBarLayout()
    }
    private func updateBarLayout() {
        let vertical = nativeBarIsVertical(in: traitCollection)
        let itemStyle: UINavigationItem.ItemStyle = vertical ? .browser : .navigator
        if navigationItem.style != itemStyle { navigationItem.style = itemStyle }
        guard contentLayoutVertical != vertical || contentConstraints.isEmpty else { return }
        NSLayoutConstraint.deactivate(contentConstraints)
        guard let scroller = nativeScrollView else { return }
        // Identical constraints before and after the consumer arrives. All hosted
        // pages reserve the header within Compose, using the measured UIKit inset.
        contentConstraints = [
            content.view.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: scroller.frameLayoutGuide.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: scroller.frameLayoutGuide.bottomAnchor),
        ]
        NSLayoutConstraint.activate(contentConstraints)
        contentLayoutVertical = vertical
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateNativeRange()
        guard view.window != nil, navigationController?.topViewController === self else { return }
        let bottom = Float(view.safeAreaInsets.bottom)
        if abs(nativeHostState.systemBottomInsetDp - bottom) > 0.5 { nativeHostState.systemBottomInsetDp = bottom }
    }

    private func updateNativeRange() {
        guard !updatingRange, let controller = scrollController, let scroller = nativeScrollView else { return }
        let scale = max(1, traitCollection.displayScale)
        let height = max(1, scroller.bounds.height)
        if controller.maxOffsetPx >= 0 {
            nativeRange = CGFloat(controller.maxOffsetPx) / scale
        } else if controller.canScrollForward {
            // Virtualized content supplies exact consumed deltas and an exact end flag.
            // Reserve viewport-sized headroom; never infer an offset from item indices.
            nativeRange = max(nativeRange, renderedOffset + height * 2)
        } else {
            nativeRange = renderedOffset
        }
        let size = CGSize(width: max(1, scroller.bounds.width), height: height + nativeRange)
        if abs(scroller.contentSize.height - size.height) > 0.5 || scroller.contentSize.width != size.width {
            updatingRange = true
            scroller.contentSize = size
            updatingRange = false
        }

    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard !updatingRange, let controller = scrollController else { return }
        let scale = max(1, traitCollection.displayScale)
        let position = min(max(0, scrollView.contentOffset.y), nativeRange)
        let delta = position - renderedOffset
        if abs(delta) > 0.001 {
            let consumed = CGFloat(controller.consumeScroll(deltaPx: Float(delta * scale))) / scale
            renderedOffset += consumed
            if delta > 0 && consumed < delta - 0.5 {
                // Compose reports the actual end; let UIKit own the ensuing rubber band.
                nativeRange = renderedOffset
                updatingRange = true
                scrollView.contentSize.height = scrollView.bounds.height + nativeRange
                updatingRange = false
            }
        }
        // The displacement comes directly from UIKit's rubber band, with no app timer,
        // easing curve, accumulated gesture translation or independent animation.
        // Move only the scrolling viewport. Translating the Compose root also moves
        // fixed overlays such as the assignment scroll-to-top button.
        controller.setOverscrollTranslation(translationPx: Float(-(scrollView.contentOffset.y - renderedOffset) * scale))
        updateTopEdgeEffect()
        updateOverscrollGlass(scrollView)
        if controller.maxOffsetPx < 0 && controller.canScrollForward && renderedOffset > nativeRange - scrollView.bounds.height {
            updateNativeRange()
        }
    }
}
