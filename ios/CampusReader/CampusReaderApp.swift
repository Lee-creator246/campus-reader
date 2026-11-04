import SwiftUI

@main struct CampusReaderApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ReaderShell(model: model, portal: model.portal)
                .task { await model.start() }
                .onChange(of: phase) { next in
                    if next == .background {
                        BackgroundCoordinator.shared.schedule(enabled: model.settings.backgroundRefresh, minutes: model.settings.intervalMinutes)
                    }
                }
        }
    }
}
