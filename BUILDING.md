# 构建说明

当前产品为 **北邮人信息 0.9.0-campus / versionCode 14**，应用 ID `org.campusreader.reader`，Java 包名前缀 `org.campusreader`。

## 工具链

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.11.1，分发包 SHA-256 固定 |
| Android Gradle Plugin | 8.10.1 |
| Android compile SDK / Build Tools | 36 / 36.0.0 |
| Java 源码 | Java 8，启用 core-library desugaring |

设置 `JAVA_HOME` 和 `ANDROID_HOME`，或在已忽略的 `local.properties` 中配置 `sdk.dir`。首次构建需要访问 Gradle、Google Maven 与 Maven Central；代理和代理凭据只保存在本机。

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
./gradlew :checks:verify
```

Windows 使用 `gradlew.bat`。核心检查使用合成数据，不登录学校或调用付费模型，不编译 Android 模块与专有 AAR；首次仍可能需要下载 Gradle 和构建插件。

## 完整 Android 构建

从 [厂商官方页面](https://support.sangfor.com.cn/productSoftware/list?product_id=19&category_id=94) 自行取得有权使用的 Android SDK。现有适配基于 `SDK2.7.10.1_0830/Android/AndroidSDK/SangforSDK.aar`；其他版本需核对 API、ABI 和授权。

可将 AAR 放入已忽略的 `vendor/sangfor/SangforSDK.aar`，或指定外部文件：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PsangforSdk=/absolute/path/SangforSDK.aar
./gradlew :app:assembleRelease :app:lintRelease -PsangforSdk=/absolute/path/SangforSDK.aar
```

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug "-PsangforSdk=C:/SDKs/SangforSDK.aar"
```

缺少 AAR 时 Android 构建明确报错。SDK 由 App 打包一次，认证模块使用 `compileOnly`；它不是任意学校都可使用的通用 VPN 库。

调试产物为 `app/build/outputs/apk/debug/app-debug.apk`，Release 未签名产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。Release 须在仓库外用维护者自己的私有密钥签名并运行 `apksigner verify`。只有同应用 ID、同签名的升级才能直接覆盖已有安装；卸载会删除本机数据。

Windows 上若发生 HotSpot C2 编译器崩溃，可临时添加 `"-Dorg.gradle.jvmargs=-Xmx3g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"`，不跳过 Lint 或测试。

## 本版接入方式

`BuptPreset` 包含公开服务地址，产品启动不再显示学校目录、配置导入或其他学校选择：

| 用途 | 入口 |
| --- | --- |
| 门户列表 | `http://my.bupt.edu.cn/xs_index.jsp?urltype=tree.TreeTempUrl&wbtreeid=1541` |
| 统一认证 | `https://auth.bupt.edu.cn/authserver/login`，完整 `service` 回跳由预设维护 |
| 校外 VPN | `https://vpn.bupt.edu.cn` |

首屏是原生门户登录。`PortalAccess` 先作无凭据的有界可达性检测；直连失败且属于网络不可达时才转 VPN，认证过期、未知页面或服务器错误分别处理。校园 Wi-Fi 与移动数据同时存在时，可将校园 HTTP 请求绑定到可用 Wi-Fi，不把 AI 或问卷流量统一绑入校园网络。

门户密码仅向识别并校验的 HTTPS 认证表单提交，成功解析门户列表后才可保存到本机加密凭据库。已有会话优先复用，前台恢复次数和间隔有限；验证码、动态码或未知认证流程需人工处理。只有用户需要并同意 VPN 服务时才初始化厂商 SDK，直连不伪造 VPN 连接成功。

门户的 HTTP 列表及认证回跳不能提供端到端加密。应用限制可访问主机、HTTP 范围和 HTTPS 降级，不关闭证书校验。公开域名是产品配置，账号、票据、密码和密钥不是构建输入。

升级时保留有效的旧北邮配置及其站点范围；直连 / VPN 切换不改变同一门户账号的缓存范围。非本产品支持的旧配置不会被当成新学校入口使用。同名账号在不同站点范围的数据不会自动合并。

## AI 配置与后台行为

AI 默认关闭。安装后填写自己的 HTTPS 服务、模型和 API Key；DeepSeek 可选官方预设，通用接口要求支持兼容 `chat/completions`、JSON 对象输出及相应请求参数。更换地址须重新提供密钥；模型名称、价格和数据政策以所选服务为准。

“保存并开启自动摘要”是对当前服务和发送范围的一次明确授权。自动发送政策片段另行控制；未开启自动模式时仍可逐篇预览确认。自动与手动共享每日尝试上限，默认 20 次，可调 1—100 次；输出最多 2,000 tokens，详情见 [AI_SUMMARY.md](docs/AI_SUMMARY.md)。

门户后台刷新默认 30 分钟，由 `JobScheduler` 择机执行；网络观察补查只在进程存活时生效。重启或更新后恢复登记，后台不索取 VPN 动态码、不自动进行完整门户密码登录。AI 自动队列由独立任务恢复，不能承诺系统永不终止或准时执行。用户自选的通知时间使用非精确闹钟。

通知摘要静默替换使用 API 24 起的 `Notification.Builder.recoverBuilder`；API 23 跳过此增强，初始标题通知与 App 内摘要仍可使用。替换必须核对仍存在的发布批次、账号、稳定编号、正文版本和规则，不把 AI 完成作为历史新通知。

主机测试使用 JUnit 4、Robolectric 和模拟服务。它们不能代替真机上的 WebView、KeyStore、隧道、权限、省电和通知验证；本轮实际结果以 [VALIDATION.md](docs/VALIDATION.md) 为准，不从构建成功推断模型质量。

## 历史通用配置接口（开发回归）

`CampusConfigImport`、学校目录解析和合成配置测试仍用于开发回归，不是本版面向用户的入口。维护者调整适配器时可参考旧的 version 1 数据格式：

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

示例是合成地址。解析器限制 16 KiB UTF-8、版本和字段白名单，并拒绝 URL 内的凭据或票据。修改参数不意味着自动支持另一套门户或认证系统；旧的 `campus.local.properties` 不参与构建。

## 源码包与发布边界

```sh
python scripts/check_public_tree.py
python scripts/package_source.py
```

源码包按当前版本生成 `campus-reader-0.9.0-source.zip`，包含文件清单及校验信息。公开预设可以包含学校名称和域名；不得包含个人配置、真实通知样本、凭据、API Key、私有签名、设备数据或专有 SDK。问卷无默认接收服务器，不自动收集本机画像或使用记录。

- 当前仅 `arm64-v8a`，最低 API 23；`targetSdk=30` 沿用厂商 SDK 验证基线，更高 target 下的隧道、权限与后台行为需另验。
- 验证用厂商原生库存在 16 KB 页对齐限制，需要兼容二进制后重新验证。
- 对外分发含 SDK 的 APK 前须核实其再分发许可；本项目 MIT 不覆盖厂商 SDK、第三方商标或校园内容。
- 本机签名、SDK 和服务密钥不提交到公开源码或 CI 日志。
