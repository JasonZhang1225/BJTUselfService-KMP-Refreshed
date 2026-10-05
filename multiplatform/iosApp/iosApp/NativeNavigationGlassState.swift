import Foundation

/// One shared navigation-bar surface, with scroll progress owned by each page.
/// Offscreen page callbacks update their cache without changing the visible bar.
final class NativeNavigationGlassState<Owner: Hashable> {
    private var progressByOwner: [Owner: CGFloat] = [:]
    private(set) var visibleOwner: Owner?

    @discardableResult
    func show(_ owner: Owner) -> CGFloat {
        visibleOwner = owner
        return progressByOwner[owner] ?? 0
    }

    func update(_ progress: CGFloat, for owner: Owner) -> CGFloat? {
        let value = progress.isFinite ? min(max(progress, 0), 1) : 0
        progressByOwner[owner] = value
        return visibleOwner == owner ? value : nil
    }

    func retain(_ owners: Set<Owner>) {
        progressByOwner = progressByOwner.filter { owners.contains($0.key) }
        if let visibleOwner, !owners.contains(visibleOwner) { self.visibleOwner = nil }
    }
}
