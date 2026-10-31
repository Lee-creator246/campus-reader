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

## 安装后配置站点

构建不读取校园地址。安装后打开“校园配置”，向学校确认自己有权使用的服务参数，再在 App 内填写：

| 界面字段 | 含义及合成示例 |
| --- | --- |
| 校园认证域 | 账号认证域，例如 `campus.example.edu`，不含协议、端口或路径 |
| VPN HTTPS 入口 | SDK 对应的认证服务，例如 `https://vpn.campus.example.edu/`；也可显式填写受信任服务提供商的外部 HTTPS 域名 |
| 门户通知列表地址 | 适配器支持的完整通知列表，例如 `https://portal.campus.example.edu/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1000` |
| 门户登录地址（可选） | 例如 `https://auth.campus.example.edu/login`；留空时使用通知列表地址作为登录入口 |

这些示例仅说明格式，不对应可用服务，也不会自动填入 App。通知列表主机必须属于校园认证域或其子域，可使用 HTTP 或 HTTPS；登录页须使用校园域内的 HTTPS，或与门户同主机的 HTTP。网址仅允许协议对应的标准端口，不得含内嵌账号密码或登录票据；登录入口可保留页面路由所需的片段，VPN 和通知列表地址不可含片段。

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

当前应用版本为 `0.5.0-campus`，版本号为 `8`，公开版应用 ID 为 `org.campusreader.reader`，Java 包名前缀为 `org.campusreader`。不同包名的安装属于独立应用，不会自动接管其他包名下的缓存或登录状态。新版按站点配置及账号建立缓存命名空间，不会自动将旧版仅按账号保存的数据归入新站点。

## 正式发行前

- 当前仅打包 `arm64-v8a`，最低 API 23，`targetSdk=30` 是沿用 SDK 验证基线。尚未完成更高 target 下的厂商隧道、权限和后台行为验证，不应据此声称可直接提交应用商店。
- 已知验证用厂商原生库存在 16 KB 页对齐限制，应由厂商提供兼容二进制后重新验证。
- Release 构建未配置签名。维护者应在本机或安全的私有 CI 中配置自己的签名，不提交密钥、密码或 SDK 文件。
- 对外发布包含 SDK 的 APK 前，单独核实 SDK 及依赖的再分发许可。本源码包不附 APK。
- 门户结构、认证策略、系统电池优化等外部条件可能变化。门户失效时保留缓存，并明确提示重新认证。

Windows 上若遇到 HotSpot C2 编译器崩溃，可在本次构建临时添加 `"-Dorg.gradle.jvmargs=-Xmx3g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"`；这不会关闭 Lint 或测试。
