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
./gradlew :checks:verify -PcampusTestDefaults=true
```

Windows：`gradlew.bat :checks:verify -PcampusTestDefaults=true`。测试使用生产 Java 实现和合成数据，包含关键词解析、通知身份、门户解析、RSS、刷新协调、元数据更新及媒体输入边界。它们不进行校园网络认证或真实通知抓取；Gradle 首次解析 Android 插件仍需要下载插件，但此任务不编译 Android 模块、不读取厂商 AAR。

`-PcampusTestDefaults=true` 会忽略本机站点配置，强制使用示例地址。公开测试和 CI 使用此参数；需要实际登录的 App 构建不要添加它。

## 配置站点

公开配置只使用示例域名。将根目录 `campus.example.properties` 复制为 `campus.local.properties`，再填写自己有权使用的站点参数：

```properties
campus.domain=campus.example.edu
campus.vpnUrl=https://vpn.campus.example.edu
campus.portalListUrl=http://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000
```

- `campus.domain`：校园账号认证域；也用于校园资源地址的边界检查。
- `campus.vpnUrl`：认证 SDK 对应的服务入口。
- `campus.portalListUrl`：门户中通知列表的完整地址。

认证入口必须使用 HTTPS，门户通知地址可使用 HTTP 或 HTTPS。两个地址的主机都必须属于 `campus.domain` 或其子域，使用协议默认端口，且不含内嵌凭据或片段标识。示例值仅用于构建和合成测试，不对应可用服务。

`campus-config` 在构建时生成 `CampusSite` 使用的常量。`campus.local.properties` 已被 Git 忽略，不应提交、附到 Issue 或放入源码包；其中也不要填写账号、密码或令牌。未完成真实站点配置时，App 禁止校园登录并提示“请先配置校园访问参数并重新构建应用”。修改本机配置后需要重新构建，站点地址会包含在生成的 App 中。

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

当前应用版本为 `0.4.2-campus`，公开版应用 ID 为 `org.campusreader.reader`，Java 包名前缀为 `org.campusreader`。不同包名的安装属于独立应用，不会自动接管其他包名下的缓存或登录状态。

## 正式发行前

- 当前仅打包 `arm64-v8a`，最低 API 23，`targetSdk=30` 是沿用 SDK 验证基线。尚未完成更高 target 下的厂商隧道、权限和后台行为验证，不应据此声称可直接提交应用商店。
- 已知验证用厂商原生库存在 16 KB 页对齐限制，应由厂商提供兼容二进制后重新验证。
- Release 构建未配置签名。维护者应在本机或安全的私有 CI 中配置自己的签名，不提交密钥、密码或 SDK 文件。
- 对外发布包含 SDK 的 APK 前，单独核实 SDK 及依赖的再分发许可。本源码包不附 APK。
- 门户结构、认证策略、系统电池优化等外部条件可能变化。门户失效时保留缓存，并明确提示重新认证。

Windows 上若遇到 HotSpot C2 编译器崩溃，可在本次构建临时添加 `"-Dorg.gradle.jvmargs=-Xmx3g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"`；这不会关闭 Lint 或测试。
