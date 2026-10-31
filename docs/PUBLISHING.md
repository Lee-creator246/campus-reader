# 发布到 GitHub

使用此项目目录或解压后的源码包作为仓库根目录。发布内容仅包含公开源码、文档和合成测试，不包含本机配置、账号信息、真实校园内容、APK、SDK 或工具缓存。

首次发布到新仓库时，在 GitHub 创建空仓库（例如 `campus-reader`，MIT 许可证已在本目录），然后在本项目目录执行：

```sh
git init -b main
git add .
git update-index --chmod=+x gradlew
git diff --cached --stat
python scripts/check_public_tree.py --tracked
git commit -m "Initial public source release"
git remote add origin https://github.com/YOUR_USERNAME/campus-reader.git
git push -u origin main
```

把 `YOUR_USERNAME` 换成自己的 GitHub 用户名。已有仓库应沿用其远程地址和分支，不重复初始化或添加同名远程。源码打包脚本只生成本地产物。

`.gitignore` 排除了 SDK、APK、签名、机器配置、`campus.local.properties`、缓存和调试输出。检查脚本能发现常见误提交，但不能取代人工核对；在提交前查看暂存区内容，确认没有个人、具体学校或真实服务信息。

GitHub Actions 只执行公开文件检查与合成 JVM 测试；各套件在测试进程中显式安装通用配置，不需要校园账号、SDK、实际站点样本或仓库 Secrets。生产 App 默认没有站点配置，用户安装后自行填写。不要把自己的凭据放入 Issue、工作流变量或测试数据。

生成新的源码 ZIP：

```sh
python scripts/package_source.py
```

脚本使用固定目录/文件清单及排除规则，验证后输出到 `dist/`，同时生成 SHA-256 文件；不会打包 `.git`、SDK 或构建产物。运行时配置保存在设备的应用私有存储，不应导出进源码包。

本轮仅生成供本机核验的 `0.5.0-campus` 签名 APK，不自动上传 GitHub Release。签名成功不等于获得厂商 SDK 再分发许可：对外发布任何包含 SDK 的 APK 前，须单独确认 SDK、原生库及依赖许可，并保管自己的签名密钥。没有明确许可时，只发布公开源码及构建说明。
