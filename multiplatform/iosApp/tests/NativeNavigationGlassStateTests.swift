import Foundation

@main
struct NavigationGlassRegression {
    static func main() {
        let state = NativeNavigationGlassState<String>()
        var checks = 0
        func expect(_ actual: CGFloat?, _ expected: CGFloat?) {
            precondition(actual == expected, "Expected \(String(describing: expected)), got \(String(describing: actual))")
            checks += 1
        }
        expect(state.show("more"), 0)
        for progress in [CGFloat(0), 0.2, 0.5, 0.8, 1] { expect(state.update(progress, for: "more"), progress) }
        for detail in ["settings", "calendar", "report-card", "settings", "calendar"] {
            expect(state.show(detail), 0)
            expect(state.update(1, for: "more"), nil) // hidden root recomposes; detail stays clear
            expect(state.update(0, for: detail), 0)
            expect(state.show("more"), 1) // pop restores without another scroll
            state.retain(["more"])
        }
        expect(state.show("settings"), 0)
        expect(state.update(0.4, for: "settings"), 0.4)
        expect(state.show("more"), 1)
        expect(state.show("settings"), 0.4) // interactive pop cancelled
        expect(state.update(0, for: "more"), nil)
        expect(state.show("more"), 0)
        expect(state.update(-2, for: "more"), 0)
        expect(state.update(2, for: "more"), 1)
        expect(state.update(.nan, for: "more"), 0)
        print("Navigation glass regression: \(checks) checks passed")
    }
}
