# 构建说明

## 工具链

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.11.1，分发包 SHA-256 已固定 |
| Android Gradle Plugin | 8.10.1 |
| Android compile SDK / Build Tools | 36 / 36.0.0 |
| Java 源码 | Java 8，启用 Android core-library desugaring |

将 `JAVA_HOME` 指向 JDK 17。完整 Android 构建需要 `ANDROID_HOME` 指向 Android SDK，或在根目录创建本机 `local.properties` 并设置 `sdk.dir`。该文件已被 Git 忽略。也可在 Android Studio 打开根目录导入工程。

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

首次构建需访问 Gradle 分发站、Google Maven、Maven Central。Wrapper 固定版本和校验值，仓库不附带整套 JDK、SDK 或 Gradle 缓存。

若首次 Wrapper 下载超时，请检查本机网络或按 Gradle 官方文档配置自己的代理；不要把代理凭据提交到仓库。也可使用同版本 Gradle 8.11.1 执行相同任务。

## 先运行不依赖厂商的核心测试

```sh
./gradlew :checks:verify
```

Windows：`gradlew.bat :checks:verify`。测试使用生产 Java 实现和合成数据，包含运行时站点校验、跨站点账号隔离、关键词解析、通知身份、门户解析、RSS、刷新协调、元数据更新及媒体输入边界。它们不进行校园网络认证或真实通知抓取；Gradle 首次解析 Android 插件仍需要下载插件，但此任务不编译 Android 模块、不读取厂商 AAR。

测试代码显式安装合成站点配置。生产代码启动时没有默认校园地址，不需要测试专用构建参数。

Android 本地单元测试还覆盖规则存储、提醒决策、SQLite 摘要缓存和模拟网络响应，需要完整 Android 构建依赖及厂商 AAR 才能编译：

```sh
./gradlew :app:testDebugUnitTest -PsangforSdk=/absolute/path/SangforSDK.aar
```

这些测试使用 JUnit 4 和 Robolectric，不会登录校园网络或调用付费模型。Robolectric 模拟环境不能代替设备上的 WebView、AndroidKeyStore、厂商隧道和系统通知验证。本版实际运行结果以 [docs/VALIDATION.md](docs/VALIDATION.md) 为准。

## 安装后配置站点

构建不读取校园地址。安装后打开“校园配置”，向学校确认自己有权使用的服务参数，再在 App 内填写：

| 界面字段 | 含义及合成示例 |
| --- | --- |
| 校园认证域 | 账号认证域，例如 `campus.example.edu`，不含协议、端口或路径 |
| VPN HTTPS 入口 | SDK 对应的认证服务，例如 `https://vpn.campus.example.edu/`；也可显式填写受信任服务提供商的外部 HTTPS 域名 |
| 门户通知列表地址 | 适配器支持的完整通知列表，例如 `https://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000` |
| 门户登录地址（可选） | 例如 `https://auth.campus.example.edu/login`；留空时使用通知列表地址作为登录入口 |

这些示例仅说明格式，不对应可用服务，也不会自动填入 App。通知列表主机必须属于校园认证域或其子域，可使用 HTTP 或 HTTPS；登录页须使用校园域内的 HTTPS，或与门户同主机的 HTTP。网址仅允许协议对应的标准端口，不得含内嵌账号密码或登录票据；登录入口可保留页面路由所需的片段，VPN 和通知列表地址不可含片段。

### 导入配置文件或粘贴配置

配置页面支持从文件选择器读取 UTF-8 JSON，也可粘贴同样格式的文本。下面是完整的合成示例：

```json
{
  "format": "campus-reader-site",
  "version": 1,
  "domain": "campus.example.edu",
  "vpnUrl": "https://vpn.campus.example.edu/",
  "portalListUrl": "https://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000",
  "loginUrl": "https://auth.campus.example.edu/login"
}
```

`loginUrl` 可以省略或设为空字符串。文件和粘贴文本最多 16 KiB，只接受上述字段，拒绝重复或未知字段、非支持版本以及夹带登录凭据的网址。不要添加 `username`、`password`、Cookie、动态码、SDK 票据或 AI Key。

解析成功后先显示四项地址与当前草稿的差异，确认后填入草稿，再由用户阅读服务说明并保存。取消或格式无效不会覆盖已保存配置或断开现有连接。导入本身不连接服务、不替用户勾选同意，也不是订阅、阅读状态或登录会话备份。

阅读并同意连接服务说明后才能保存和启用校园连接。SDK 可能处理设备、系统和网络信息以完成认证及学校安全检查；首次配置前不初始化连接 SDK。首次保存后可立即进入校园认证，再单独完成门户登录。已有授权会话可在返回前台时尝试恢复。

修改配置会退出连接、清除门户 Cookie、网页存储和客户端证书选择，并保留按站点配置和账号隔离的本地缓存。SDK 尚未启动时可直接应用新配置；已尝试初始化 SDK 时，保存后应用会关闭，需从桌面重新打开。此流程不需要重新构建 APK。用户输入保存在应用私有存储；旧 `campus.local.properties` 仍被 Git 忽略，但不再作为构建输入。

为兼容运行时填写的旧门户，Android 网络安全配置允许明文请求；应用的校园请求和门户 WebView 另行将 HTTP 限制到所配置的门户主机，公共 RSS 仅允许 HTTPS。HTTP 门户不能提供端到端传输加密。

解析器只适配当前实现支持的页面、分页和附件结构，认证代码也依赖所接入 SDK。为其他站点填写地址后，仍需验证账号域、认证策略、HTML/XML 结构及资源访问范围；必要时修改对应适配器。

## 接入 Sangfor SDK

从 [厂商官方 SDK 页面](https://support.sangfor.com.cn/productSoftware/list?product_id=19&category_id=94) 获取符合自己使用授权的 Android SDK。当前适配基于 SDK2.7.10.1_0830 分发包中的 `Android/AndroidSDK/SangforSDK.aar`；其他版本的 API、ABI 和授权条件需单独确认。

可以把 AAR 放到已忽略的 `vendor/sangfor/SangforSDK.aar`，或者在命令中指定外部文件：

```sh
./gradlew :app:assembleDebug :app:lintDebug -PsangforSdk=/absolute/path/SangforSDK.aar
```

PowerShell 示例（将路径替换为自己的文件）：

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug "-PsangforSdk=C:/SDKs/SangforSDK.aar"
```

缺少 AAR 时，Android 构建会明确报错。校园连接依赖 Sangfor SDK 及目标系统支持的认证策略，不提供通用 VPN 兼容性。SDK 只由 App 打包一次，认证库使用 `compileOnly`。

调试 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。Android Gradle Plugin 在本机生成调试签名；源码包不携带签名文件。其他开发者的调试签名通常不同，不能直接覆盖你已有的同包名安装；卸载会清除数据，不要把它当作无损升级方式。

本轮发布构建使用 `:app:assembleRelease :app:lintRelease`，生成 `app/build/outputs/apk/release/app-release-unsigned.apk`。该产物须由维护者使用自己的私有签名密钥另行签名，并通过 `apksigner verify` 检查。本轮交付的本地 APK 已在仓库之外签名；源码不附该密钥。

当前应用版本为 `0.7.0-campus`，版本号为 `11`，公开版应用 ID 为 `org.campusreader.reader`，Java 包名前缀为 `org.campusreader`。不同包名的安装属于独立应用，不会自动接管其他包名下的缓存或登录状态。按站点配置及账号建立缓存命名空间，不会自动将旧版仅按账号保存的数据归入新站点；同包名、同签名升级时，数据库迁移保留阅读状态并增加摘要表。

## 配置可选 AI 摘要

首装不预设服务方、模型或密钥，也不会在刷新通知时自动调用 AI。进入侧栏的 AI 设置，为当前站点和账号填写：

- 完整 HTTPS `chat/completions` 地址，例如 `https://api.example.com/v1/chat/completions`；这个示例不是可用服务。地址不得夹带凭据、查询参数或片段。
- 服务方提供的模型 ID，服务须支持 `response_format: {"type":"json_object"}`、`messages`、`temperature` 和 `max_tokens` 参数。
- 用户自己的 API Key；启用并保存。保存不会发送测试请求。

响应应为兼容的 `choices[0].message.content` JSON 文本，且 `finish_reason` 为 `stop`。目前不支持需要其他认证方式、不同响应结构或仅支持流式输出的接口。部分标为兼容的模型仍可能拒绝这些参数，应先核对服务文档；本版未使用真实服务账号验证具体服务方。

在有文字正文的通知中选择“AI 摘要”，确认页展示服务方和完整发送文字。每次最多 12,000 字符，输出上限 1,000 tokens；每个本地账号每天最多 20 次尝试。超长文章直接拒绝，不静默截断。已缓存摘要直接读取，不因普通刷新重复计费；主动重新生成需再次确认。失败、超时或取消可能已产生服务费用，都会计入本机尝试次数，没有自动重试；本机限制不等于服务端账单预算。

API Key 使用 AndroidKeyStore 的 AES/GCM 加密保存，不出现在配置导入或源码包中。更换服务地址须重新填写对应密钥，避免将旧密钥发给新服务。关闭 AI 保留已有摘要；可分别删除密钥、清空当前账号的摘要。清除正文或正文变化会使相应摘要失效，原有阅读状态、标签和稍后读独立保留。

## 生成源码包

```sh
python scripts/check_public_tree.py
python scripts/package_source.py
```

源码包名称为 `campus-reader-0.7.0-source.zip`，包含文件清单与校验信息，不包含 SDK、实际配置、账号、API Key、私有签名和设备数据。安装包可另命名为 `campus-reader-0.7.0.apk`；打包及签名结果以交付时的校验文件为准。

## 正式发行前

- 当前仅打包 `arm64-v8a`，最低 API 23，`targetSdk=30` 是沿用 SDK 验证基线。尚未完成更高 target 下的厂商隧道、权限和后台行为验证，不应据此声称可直接提交应用商店。
- 已知验证用厂商原生库存在 16 KB 页对齐限制，应由厂商提供兼容二进制后重新验证。
- Release 构建未配置签名。维护者应在本机或安全的私有 CI 中配置自己的签名，不提交密钥、密码或 SDK 文件。
- 对外发布包含 SDK 的 APK 前，单独核实 SDK 及依赖的再分发许可。本源码包不附 APK。
- 门户结构、认证策略、系统电池优化等外部条件可能变化。门户失效时保留缓存，并明确提示重新认证。

Windows 上若遇到 HotSpot C2 编译器崩溃，可在本次构建临时添加 `"-Dorg.gradle.jvmargs=-Xmx3g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"`；这不会关闭 Lint 或测试。

## 第 7 版：学校目录与门户恢复

通用 APK 不附具体学校目录。首次使用可在“学校与校园配置”选择“导入学校目录”，通过系统文件选择器打开独立的目录文件，然后搜索、选择学校并保存。无需编辑 JSON。已有有效配置会显示为“当前学校”；参数不变时保存直接返回，不清除登录状态。

目录也可通过用户提供的 HTTPS 地址下载并保存在本机；没有预设公共目录服务器。下载不发送校园 Cookie，不接受重定向，最多 256 KiB / 100 所学校。目录只提供连接参数，不执行脚本或授予保存密码权限；其他学校需要验证连接方案、门户解析及认证适配。

目录格式（以下全部为合成示例）：

```json
{
  "format": "campus-reader-schools",
  "version": 1,
  "schools": [{
    "id": "sample",
    "name": "示例大学",
    "connection": "sangfor",
    "portalAdapter": "campus-html-v1",
    "domain": "campus.example.edu",
    "vpnUrl": "https://vpn.campus.example.edu/",
    "portalListUrl": "https://portal.campus.example.edu/notices",
    "loginUrl": "https://sso.campus.example.edu/authserver/login"
  }]
}
```

打开时刷新、前台门户恢复默认开启，旧设置中的明确关闭值会保留；后台刷新与新通知提醒继续沿用原设置。系统通知、自启动、电池管理权限仍由系统和用户决定，SDK 服务同意仍需本人选择，AI 默认关闭。

门户恢复先访问学校登录入口以复用统一认证会话。识别支持的 HTTPS CAS 表单后，手动登录页可使用学工号和门户固定密码；“记住密码”默认勾选且按钮明确标为“保存并登录”，可取消勾选。验证码、未知页面和密码错误需手动处理。保存只在本次提交后通知列表验证成功时进行；原生表单要求与当前校园连接相同的学工号，手动网页也需使用同一账号。

自动恢复仅在前台、每次至多提交一次；失败或错误后暂停自动密码尝试，普通尝试有冷却。自动页 30 秒超时返回缓存。凭据密文保存在禁止备份的应用目录，按站点和账号隔离，绑定精确 HTTPS 登录主机与路径；不会保存六位校园动态密码。设置中的“删除本机门户密码”离线可用。清除密码不代表退出学校服务器端的已有会话。

本轮无手机，真实 CAS 脚本提交、验证码、平台 KeyStore 和厂商连接恢复需另做设备验收。
