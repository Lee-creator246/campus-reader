# 第三方材料与许可证范围

项目自有 Java 代码、公开版重新编写的布局、几何图标、脚本及文档使用根目录 MIT 许可证。不能用该许可证替代其他权利人的授权。

| 材料 | 本仓库中的处理 |
| --- | --- |
| Gradle 8.11.1 Wrapper（脚本及 JAR） | 保留官方生成文件，受 Apache-2.0 及其包含的第三方条款约束；完整分发许可证见 `licenses/GRADLE-LICENSE.txt` |
| AndroidX DrawerLayout / SwipeRefreshLayout、Android 构建工具、desugar 库 | Gradle 从官方仓库下载，源码包不附二进制；适用各自上游许可证 |
| jsoup 1.23.2 | Gradle 从 Maven Central 下载，适用 [上游 MIT 许可证](https://github.com/jhy/jsoup/blob/master/LICENSE) |
| Sangfor SDK | 专有外部依赖，仅提供本项目编写的适配代码，不收录 AAR、SO、厂商文档或 Demo；使用者自行获取使用授权 |
| 校园门户内容与账号信息 | 不随源码发布；以合成数据进行公开测试 |

Gradle Wrapper 来源为 Gradle 8.11.1 官方生成产物，分发包 SHA-256 固定在 `gradle/wrapper/gradle-wrapper.properties`。上游完整许可证：[Gradle 8.11.1 LICENSE](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE)。

SDK 的使用与分发条件应向 [厂商官方支持入口](https://support.sangfor.com.cn/productSoftware/list?product_id=19&category_id=94) 核实。项目 MIT 许可证不改变上游依赖的授权范围。
