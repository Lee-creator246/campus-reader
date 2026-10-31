# 公开源码验证范围

版本：`0.4.2-campus`。验证日期：2026-10-31。以下结果针对本轮最终修改后的公开源码。

## 本轮实际执行

使用 JDK 17 和本机 Gradle 8.11.1 发行版完成检查。Wrapper 首次下载超时，因此本轮不记录为 Wrapper 启动成功。构建临时使用 C1 JVM 参数，设置方式见 [BUILDING.md](../BUILDING.md)；未关闭测试或 Lint。以下 `gradle` 代表本轮使用的同版本本机发行版：

```sh
gradle :checks:verify -PcampusTestDefaults=true
gradle :app:assembleDebug :app:lintDebug -PcampusTestDefaults=true -PsangforSdk=/absolute/path/SangforSDK.aar
```

`:app:assembleDebug` 与 `:app:lintDebug` 均成功，Lint 为 **0 错误、50 警告**。该结果包含清理 SDK 原始错误信息和登录提示中的主机信息后的源码。Android 构建使用外部有权使用的 Sangfor AAR；工具链及接入方式见 [BUILDING.md](../BUILDING.md)。

在分页路径和类别也改为合成数据后，最终 `:checks:verify` 检查 **208 项全部通过**：

| JVM 检查 | 通过项数 |
| --- | ---: |
| 稳定身份 | 13 |
| 关键词词表边界 | 60 |
| 媒体输入、重定向、流及文件名 | 31 |
| 元数据刷新与正文回收 | 15 |
| 合成门户解析 | 34 |
| 刷新协调与部分失败 | 23 |
| RSS / Atom | 16 |
| 刷新锁与时间边界 | 16 |
| 合计 | 208 |

以上任务均通过 `-PcampusTestDefaults=true` 强制使用示例站点，未使用真实账号，也未进行真机回归。生成的示例配置 App 不允许真实校园登录。核心任务使用生产 Java 实现和合成数据，无需校园账号、Android SDK 或厂商 AAR；首次执行仍需下载构建依赖。

## 尚需单独验证的行为

- JVM 合成测试不能代替 Android SQLite、界面、设备通知或校园连接验证。
- 校园动态码、门户 SSO、账号切换和真实内容访问，需在有权使用且已配置的目标系统中验证。
- 新通知的横幅与声音、系统后台调度及电池管理行为，需在目标 Android 版本上验证。
- 当前门户解析器依赖特定页面结构；替换站点参数后，需要验证或调整页面、分页和附件适配。
- GitHub Actions 的实际执行结果应以对应远程运行记录为准。
