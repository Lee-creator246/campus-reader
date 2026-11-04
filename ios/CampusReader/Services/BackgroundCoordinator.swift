import UIKit
import BackgroundTasks
import UserNotifications

@MainActor final class BackgroundCoordinator {
    static let shared = BackgroundCoordinator()
    static let identifier = "org.campusreader.ios.refresh"
    weak var model: AppModel?
    func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifier, using: .main) { backgroundTask in
            Task { @MainActor in
                guard let refresh = backgroundTask as? BGAppRefreshTask, let model = self.model, model.settings.backgroundRefresh else { backgroundTask.setTaskCompleted(success: false); return }
                self.schedule(enabled: true, minutes: model.settings.intervalMinutes)
                let job = Task { @MainActor in
                    await model.start(background: true)
                    await model.refresh(background: true)
                    refresh.setTaskCompleted(success: !Task.isCancelled && model.lastRefreshSucceeded)
                }
                refresh.expirationHandler = { job.cancel() }
            }
        }
    }
    func schedule(enabled: Bool, minutes: Int) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
        guard enabled else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.identifier)
        request.earliestBeginDate = Date().addingTimeInterval(Double(max(15, min(240, minutes))) * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}

@MainActor final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        ReaderNotifications.shared.register(); BackgroundCoordinator.shared.model = AppModel.shared; BackgroundCoordinator.shared.register()
        return true
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        Task { @MainActor in
            defer { completionHandler() }
            guard let model = BackgroundCoordinator.shared.model,
                  response.notification.request.content.userInfo["profile"] as? String == model.profileID,
                  let id = response.notification.request.content.userInfo["notice"] as? String,
                  let notice = model.library.notices.first(where: { $0.id == id }) else { return }
            switch response.actionIdentifier {
            case "READ": model.read(id, true)
            case "LATER": model.later(id, true)
            case UNNotificationDefaultActionIdentifier: model.selectedNotice = notice
            default: break
            }
        }
    }
}
