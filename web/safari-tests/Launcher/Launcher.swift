import SwiftUI
import UIKit

/// The test opens the same synthetic site in Mobile Safari even if Xcode starts a fresh simulator clone.
@main
struct Launcher: App {
    var body: some Scene {
        WindowGroup {
            Button("Open reader in Safari") {
                UIApplication.shared.open(URL(string: "http://127.0.0.1:18765/campus-reader/")!)
            }
            .accessibilityIdentifier("open-reader")
        }
    }
}
