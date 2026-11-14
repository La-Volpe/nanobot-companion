import SwiftUI

@main
struct NanobotCompanionApp: App {
    private let container = Container.forLaunch()

    var body: some Scene {
        WindowGroup {
            RootView(container: container)
        }
    }
}
