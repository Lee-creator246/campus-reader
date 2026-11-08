# 北邮人信息 · iOS 验证工程

面向 iOS 16 及以上的 iPhone 和 iPad。首屏使用北邮信息门户账号登录，读者可以查看通知、标记已读和稍后读、设置关注与屏蔽词，以及选择是否启用 AI 摘要。iOS 版本号为 0.9.1，构建号为 1。

同学在 iPhone 上安装需要签名版本或 TestFlight 邀请。下面的自动构建生成模拟器 App 和用于检查编译结果的未签名真机 App。每一次构建的状态与产物可在 [iOS 构建记录](https://github.com/Lee-creator246/campus-reader/actions/workflows/ios.yml) 中查看。

## 开发者如何构建

在 Mac 上准备完整的 Xcode、至少一个可用的 iPhone 模拟器运行时，以及 XcodeGen 2.42 或以上版本。初次构建需要联网解析 Swift 包。以下命令从仓库根目录执行。

```bash
brew install xcodegen
swift test --package-path ios/CampusReaderCore
bash ios/scripts/build.sh
```

构建脚本生成 App 图标和 `ios/CampusReader.xcodeproj`，解析依赖，并将 Release 真机构建输出到 `ios/build/device/Build/Products/Release-iphoneos/CampusReader.app`。之后可用 Xcode 打开 `.xcodeproj`，选择 `CampusReader` scheme 和 iPhone 模拟器运行。准备归档前请将团队、版本号及唯一的 Bundle ID 设置在 `project.yml` 或签名配置中，以便再次生成工程时沿用。

命令行运行模拟器上的门户服务测试和界面测试：

```bash
DEVICE=$(python3 ios/scripts/select_simulator.py)
xcodebuild -project ios/CampusReader.xcodeproj -scheme CampusReader \
  -configuration Debug -destination "platform=iOS Simulator,id=$DEVICE" \
  -parallel-testing-enabled NO -derivedDataPath ios/build/simulator \
  CODE_SIGNING_ALLOWED=NO test
```

在 Debug 构建的 Xcode scheme 中添加启动参数 `--ui-demo` 可查看合成通知，并测试长按预览、稍后读与打开正文。真实门户登录与模型请求需要单独在正常启动的 App 内验证。

## 选择正确的交付物

| 要做什么 | 使用的文件或入口 |
| --- | --- |
| 在另一台 Mac 上继续开发 | 完整源码目录及 `ios/project.yml`。`.xcodeproj` 引用同级源码和 `CampusReaderCore`，需要保留目录结构。 |
| 在 Mac 模拟器体验界面 | 选择与构建产物架构和系统版本兼容的模拟器，使用 `CampusReader-iOS-simulator.app.zip`，解压后将 `.app` 拖入模拟器；也可在自己的 Mac 上按上节直接构建。 |
| 检查真机目标是否编译 | `CampusReader-iOS-unsigned.app.zip`，包含未签名的真机 `.app`。安装到 iPhone 还需开发团队签名及对应分发配置。 |
| 发给同学在自己的 iPhone 上测试 | 有 Apple Developer Program 团队后，在 Xcode 配置签名、归档并上传 App Store Connect，建立外部测试组，首个外测构建通过 TestFlight 审核后，再分享邀请链接。参见 [Apple TestFlight 指南](https://developer.apple.com/testflight/)。 |

GitHub Actions 的 `ios-build-evidence` 会归档已生成的文件。判断一次提交是否通过验证，请核对核心测试、真机目标编译、模拟器测试各自的结果。可在 Mac 上用 Xcode 打开 `Tests.xcresult` 查看失败步骤与测试截图。

### 本人 iPhone 的免费安装与复验

GitHub Actions 使用 XcodeGen 生成 SwiftUI 工程，SwiftPM 拉取 SwiftSoup 2.9.6。真机 Release 编译目标为 `generic/platform=iOS`，签名设置为 `CODE_SIGNING_ALLOWED=NO`。新版工作流将 `CampusReader-needs-signing.ipa`、`device-audit.json` 和 `device-build.log` 放入 `ios-build-evidence` 的 `build/delivery/`。报告包含版本、提交、SHA-256、Mach-O 平台、签名命令和描述文件存在性。构建日志与项目、测试产物在同一运行中归档。

在 Windows 或 macOS 上可以复核下载的 IPA。程序用主可执行文件的 `LC_BUILD_VERSION` 区分 iOS（2）与模拟器（7），再与 Info.plist 核对。报告里的签名团队、失效时间和授权设备若为 null，表示没有证据，无法确认。对已重新签名的文件，必须另用 Apple 的签名工具和描述文件验证证书、entitlements 与设备列表。

```sh
python ios/scripts/inspect_app.py CampusReader-needs-signing.ipa --platform iphoneos
```

**有可配对的 Mac**。在 Xcode 设置中登录个人 Apple 账号。在 `ios/` 运行 `bash scripts/build.sh` 以生成工程，打开生成的 `CampusReader.xcodeproj`。选择 CampusReader target → Signing & Capabilities，启用自动签名，选择 Personal Team，设置本人可用且唯一的 Bundle ID。连接并信任已解锁 iPhone，按提示开启开发者模式，选择这部手机作为运行目标并点 Run。个人描述文件自签发起有效 7 天，到期重新构建安装，使用相同团队与标识更新。[Apple 真机运行](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices) · [免费团队的设备与有效期限制](https://developer.apple.com/help/account/basics/about-your-developer-account)

**只有 Windows**。本 IPA 的 App 最低支持 iOS 16；使用 AltStore Classic 2.3 安装时，手机需要 iOS 17.4 或更高。依官方指南准备 Windows 版 AltServer、iTunes、iCloud，使用可传数据的 USB 线连接已解锁手机并信任电脑，在 iTunes 开启 Wi-Fi 同步。用 AltServer 的 Install AltStore 为该手机安装工具，账号与二次验证由本人输入。根据手机提示信任个人开发者，在“设置 → 隐私与安全性 → 开发者模式”开启、重启并确认。把待签名 IPA 保存到 iPhone 的“文件”，从 AltStore 的 My Apps 安装。测试者应记录真实手机启动结果。免费账户安装的应用每 7 天需刷新，常规方式是在 AltServer 可连接时刷新。用于更新的账号、Bundle ID 和签名团队应保持一致，否则 iOS 可能视为另一款 App，资料与钥匙串也可能使用新的隔离空间。[Windows 安装指南](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows) · [2.3 系统要求](https://github.com/altstoreio/AltStore/releases/tag/v2.3) · [刷新方法](https://faq.altstore.io/altstore-classic/your-altstore)

**权限和首次进入**。App 使用系统分配的私有钥匙串保存会话、可选门户密码和 AI Key。重签后须实际验证首次登录、退出重开与会话恢复；遇到“当前签名缺少钥匙串访问权限”时，检查签名与描述文件的应用标识和团队。系统可能在首次校园网访问、开启本地通知、保存图片时分别询问授权。后台刷新受系统安排。门户使用 my.bupt.edu.cn 的受限 HTTP 例外与 HTTPS CAS 网页回跳；IPA 没有发布 APNs 或内嵌 VPN 的授权。

**发下一版与回退**。在 `ios/project.yml` 增加构建号，提交到受 iOS 工作流监控的分支，或在对应分支手动运行工作流。等待核心测试、真机编译、服务测试与 UI 测试，保存 `ios-build-evidence` 与报告中的提交号。用同一账号在自己的手机上重签新 IPA 并覆盖安装。需要回退时，选择已验证的旧提交再构建，用同一 Bundle ID 和团队签名；若系统拒绝覆盖较高构建号，从旧源码提高构建号再签。涉及存储格式修改时要先确认旧版能读取已有数据，卸载会清除沙盒。

## 给同学的真机验证步骤

拿到签名版本后，先用校园 Wi-Fi 测试登录和首次刷新，再测试校园网以外的行为。每项记录 App 构建号、iOS 版本、网络环境、页面提示和实际结果。反馈中的截图请遮住学工号、私人通知、验证码及密钥。

| 操作 | 应看到的结果 |
| --- | --- |
| 在校园 Wi-Fi 上首次登录 | 填学工号与门户密码；如学校提出额外验证，在学校页面本人完成。进入通知列表后，切换到“最新”，核对当日通知与学校移动列表。 |
| 连续刷新两次，再“加载更多” | 相同门户编号只显示一次；已有已读、稍后读与标签保持。可在“刷新与提醒记录”查看每次更新和新增数量。 |
| 长按未读通知预览、加入稍后读，再打开正文 | 长按预览后仍未读；打开已加载的正文才标为已读。手动标回未读后，稍后读标记仍在。 |
| 设置“任职”为屏蔽词、“志愿”为关注词 | 匹配屏蔽词的条目从列表和提醒中排除。匹配关注词的条目可显示推荐理由。用顶部主题和自定义标签进一步筛选。 |
| 打开有图片、附件的通知，再打开纯文字通知 | 图片可放大，附件可通过系统菜单保存到“文件”或分享；纯文字行直接显示标题。 |
| 再次打开 App、遇到过期会话，或转到外网 | 有效会话可继续使用。保存过密码的账号可尝试前台恢复，验证码由本人填写。连接失败或认证过期时仍可查看本机已缓存内容。 |
| 用另一账号登录，再切回原账号 | 分别查看各自的缓存、阅读状态和政策资料，检查旧账号的请求和提醒不会出现在新账号。 |

**连接判断。** 首页 `/xs_index.jsp?...wbtreeid=1541` 用于探测可达性。探测在同源认证回跳中保留本次临时 Cookie，之后销毁，与账号的登录 Cookie 分开。验证登录与同步通知实际访问 `/list.jsp?...wbtreeid=1154`，解析出移动通知列表才认定门户会话有效。学校认证页使用 HTTPS，学校现有门户列表和部分回跳使用 HTTP，因此建议在可信校园网络验证。

**校外测试。** 本工程未集成专有 aTrust iOS SDK。已通过学校允许的方式建立、且覆盖本 App 流量的校园访问通道后，可返回 App 重试。通道能否覆盖本 App 取决于学校策略；开启其他客户端本身不足以确认门户可读。

**缓存与凭据。** 选择保存的门户密码、持久会话及 API Key 由本机钥匙串按用途隔离，使用仅限本设备解锁时访问的属性。正文默认最多 300 条或 20 MiB，已读超过 24 小时可回收正文。稍后读和带标签的正文优先保留，清理正文仍保留阅读标记。达到 20,000 条阅读身份记录的上限时会提示停止加入新编号。

**AI 验证。** 在侧栏的“AI 摘要服务”配置自己的 DeepSeek 或兼容 HTTPS 服务，保存后启用自动摘要。返回列表并保持 App 在前台，观察有正文的条目是否出现概述，点句末数字核对本机原文。日尝试上限默认 20 次。截断、空回复或依据不匹配有独立错误提示，已发送的失败请求需要手动重试。可用一条合成材料先验证，日期、对象、否定条件应与引用一致。导入 TXT 或文字 PDF 后，发送政策片段需单独授权。生成结果仍需对照原文判断适用条件。

**后台与通知。** 在“阅读与通知设置”允许通知和后台刷新。首次同步建立基线，后续新增且符合规则的通知才触发本地提醒。用刷新记录核对实际执行时间，尤其要记录低电量、门户过期及切换网络时的表现。iOS 决定后台任务的调度时机，设置的间隔是期望值。此版本使用系统后台刷新与本地通知，实时远程推送需要另行部署服务。参见 [Apple 后台任务说明](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app)。
