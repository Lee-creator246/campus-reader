# 校园阅读 · Campus Reader

面向校园信息门户的 Android 本地通知阅读器。配置站点并完成校园连接和门户登录后，将通知缓存到手机，支持已读、稍后读、标签、关键词屏蔽和优先提醒。

这是独立开源项目，不代表任何学校或服务提供商。当前应用版本为 **0.4.2-campus**，仍处于验证阶段。

## 功能

- 通过接入的认证 SDK 建立校园通道，校园账号与动态密码由用户输入；信息门户账号登录单独在 App 内完成。
- 抓取通知标题、发布时间、正文、原文地址、图片和附件入口；按发布时间倒序排列，按稳定门户编号去重。
- 已读 / 未读、稍后读、标签独立管理；重复刷新保留阅读状态，登录过期保留本地内容。
- 支持列表、杂志、图片卡片视图；没有图片时使用纯文字行。
- 搜索标题和缓存正文，支持组合筛选；侧栏“关键词规则”设置屏蔽词和优先提醒词，“重点关注”查看匹配内容。
- 屏蔽词优先于提醒词；规则按账号保存，清空搜索不会清空规则，屏蔽不会删除阅读状态或标签。
- 后台定时刷新、通知渠道、缓存条数与容量设置；默认已读超过 24 小时可清正文，保留轻量历史和状态。
- 图片查看及保存、附件下载与导出，本地 RSS / Atom 订阅。

关键词只匹配标题及仍缓存的正文，不识别图片中的文字。优先提醒随后台刷新触发，受认证状态、电池管理和系统通知权限影响；新增规则不会重推所有历史通知。

## 获取与构建

需要 JDK 17、Android SDK 36 和 Build Tools 36.0.0。完整 App 还需要自行取得有权使用的 **SangforSDK.aar**；仓库不提供该专有二进制，也不会自动下载它。

```sh
# 无需厂商 SDK，执行合成数据核心测试
./gradlew :checks:verify -PcampusTestDefaults=true

# 配置 Android SDK 后，使用自己持有的 SDK 构建
./gradlew :app:assembleDebug :app:lintDebug -PsangforSdk=/absolute/path/SangforSDK.aar
```

Windows 使用 `gradlew.bat`。构建前，将 `campus.example.properties` 复制为已忽略的 `campus.local.properties`，配置自己有权使用的账号域、认证入口和门户通知地址。仓库只提供示例站点；未完成配置时，App 禁止校园登录并提示配置后重新构建。完整步骤及已知平台限制见 [BUILDING.md](BUILDING.md)。

当前解析器适配特定的门户页面结构，认证适配也依赖特定 SDK；修改地址不意味着自动兼容所有学校，需要根据目标系统验证或调整适配代码。

## 项目结构

| 模块 | 职责 |
| --- | --- |
| `campus-config` | 从本机配置生成站点参数，公开源码仅含示例值 |
| `app` | 阅读界面、设置、关键词规则、后台任务、RSS、图片和附件 |
| `campus-auth` | 官方 SDK 适配、账号认证、隧道状态与生命周期 |
| `campus-extension` | SQLite 缓存、稳定身份、阅读状态、标签、查询、缓存清理 |
| `campus-portal` | 门户列表、正文、附件和分页解析 |
| `checks` | 无需账号、网络抓取或专有 SDK 的 JVM 回归测试 |

架构与认证边界见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，验证范围见 [docs/VALIDATION.md](docs/VALIDATION.md)。

## 公开内容与本机配置

界面使用项目自有布局、几何图标和标准 Android 控件。仓库只发布源代码、构建配置及合成测试，不包含真实站点配置、校园通知样本、用户数据、登录凭据、签名私钥、设备转储或商业 SDK。第三方依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

站点参数由 `campus-config` 在构建时读取本机配置，生成 `CampusSite` 使用的常量；公开测试通过 `-PcampusTestDefaults=true` 强制使用示例站点。站点地址会进入构建产物，因此本机配置只填写服务地址，不填写账号、密码或令牌。认证后获取的内容保存在本机，不随源码发布。

## 使用与贡献

1. 按构建说明配置站点，并在有权访问的校园系统中完成校园认证和门户认证。
2. 刷新通知；需要重新登录时按 App 提示操作。
3. 在侧栏管理稍后读、标签、关键词规则和后台设置。

欢迎通过 Issue 提供不含个人信息的复现步骤。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 和 [SECURITY.md](SECURITY.md)。上传 GitHub 的本地操作说明见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

## 许可证

项目自有代码和重新编写的资源采用 [MIT License](LICENSE)。Gradle Wrapper 及通过构建工具获取的依赖保留各自许可证；MIT 不授予 Sangfor SDK、第三方产品商标或校园内容的使用及再分发权。
