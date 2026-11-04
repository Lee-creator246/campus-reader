import XCTest

final class ReaderUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }
    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testFirstScreenShowsPortalLoginWithoutCampusConfiguration() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-testing"]; app.launch()
        XCTAssertTrue(app.staticTexts["北邮人信息"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.secureTextFields.firstMatch.exists)
        XCTAssertFalse(app.buttons["导入校园配置"].exists)
        screenshot("first-login")
    }
    func testLongPressPreviewKeepsUnreadAndReadLaterAreIndependent() {
        let app = XCUIApplication(); app.launchArguments = ["--ui-testing", "--ui-demo"]; app.launch()
        let notice = app.buttons["notice-n:1001"]
        XCTAssertTrue(notice.waitForExistence(timeout: 15)); XCTAssertTrue(notice.label.contains("未读"))
        screenshot("notice-list")
        notice.press(forDuration: 1.2)
        let preview = app.buttons["预览摘要"]; XCTAssertTrue(preview.waitForExistence(timeout: 5)); preview.tap()
        XCTAssertTrue(app.buttons["关闭预览"].waitForExistence(timeout: 5)); screenshot("summary-preview")
        app.buttons["关闭预览"].tap()
        XCTAssertTrue(notice.waitForExistence(timeout: 5)); XCTAssertTrue(notice.label.contains("未读"))
        notice.press(forDuration: 1.2); app.buttons["加入稍后读"].tap()
        XCTAssertTrue(notice.label.contains("未读"))
        notice.tap(); XCTAssertTrue(app.staticTexts["通知原文"].waitForExistence(timeout: 5)); screenshot("notice-detail")
        app.buttons["关闭"].tap()
        XCTAssertTrue(notice.waitForExistence(timeout: 5)); XCTAssertTrue(notice.label.contains("已读"))
    }
}
