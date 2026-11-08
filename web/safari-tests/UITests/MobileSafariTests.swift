import XCTest

final class MobileSafariTests: XCTestCase {
    private let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func linkWithTitle(_ title: String) -> XCUIElement {
        safari.webViews.links.matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch
    }

    /// A fresh Safari installation can interpose its onboarding sheet before showing the page.
    private func waitForReader(_ row: XCUIElement) -> Bool {
        let deadline = Date().addingTimeInterval(65)
        while Date() < deadline {
            if row.waitForExistence(timeout: 2) && row.isHittable { return true }
            let labels = ["Continue", "Start Browsing", "Got It", "Not Now", "Done",
                          "继续", "开始浏览", "知道了", "稍后", "完成"]
            for label in labels {
                let button = safari.buttons[label].firstMatch
                if button.exists && button.isHittable { button.tap(); break }
            }
        }
        return false
    }

    func testOpenNoticeAndReturnInMobileSafari() {
        let launcher = XCUIApplication()
        launcher.launch()
        launcher.buttons["open-reader"].tap()
        safari.activate()

        let row = linkWithTitle("志愿服务报名通知")
        let loaded = waitForReader(row)
        if !loaded { capture("simulated-iphone-safari-launch-failure") }
        XCTAssertTrue(loaded, safari.debugDescription)
        // Fresh Safari may show a native TipKit popover. It reports the background
        // web link as hittable but consumes the first tap to dismiss the tip.
        // Tap the inert list heading before exercising the actual navigation link.
        let heading = safari.webViews.staticTexts["通知"].firstMatch
        XCTAssertTrue(heading.waitForExistence(timeout: 8), safari.debugDescription)
        heading.tap()
        capture("simulated-iphone-safari-list")
        row.tap()

        let back = safari.webViews.buttons.matching(NSPredicate(format: "label CONTAINS %@", "返回列表")).firstMatch
        let opened = back.waitForExistence(timeout: 15)
        if !opened { capture("simulated-iphone-safari-open-failure") }
        XCTAssertTrue(opened, safari.debugDescription)
        let body = safari.webViews.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@", "图书整理和活动引导")).firstMatch
        XCTAssertTrue(body.waitForExistence(timeout: 8), safari.debugDescription)
        capture("simulated-iphone-safari-detail")
        back.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 15), safari.debugDescription)
        capture("simulated-iphone-safari-return")
    }
}
