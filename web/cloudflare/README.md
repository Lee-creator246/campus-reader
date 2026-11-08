# 将阅读内测部署到 Cloudflare Workers

此目录部署独立的 `campus-reader-safari` Worker。Cloudflare 托管阅读界面，`/api` 的六个公开业务接口由你指定的现有 HTTPS VPS 提供。Cloudflare 账号会提供 `workers.dev` 地址，可用它进行首批内测。现有静态 Pages 页面适合合成通知和本机导入；此 Worker 还支持把反馈、推送订阅及公开通知请求交给 VPS。[Workers 域名](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/) · [静态资源与绑定](https://developers.cloudflare.com/workers/static-assets/binding/)

**准备项目。** 在 Windows 安装 Node.js 22 或以上版本。在源码根目录生成部署包，替换命令中的示例地址为你已上线的 VPS 公网 HTTPS 源站。源站必须运行支持手机提醒接口的服务版本。

```powershell
python web/cloudflare/build.py --output dist/cloudflare --upstream "https://reader.example.edu"
cd dist/cloudflare/worker
```

也可以直接解压 `campus-reader-cloudflare-worker.zip`，在含 `wrangler.json` 的目录执行以下命令。如果包中 `vars.UPSTREAM_ORIGIN` 为空，填写 VPS 的 HTTPS 源站地址。此变量只接受不带账号、路径、查询参数的 HTTPS origin。管理令牌和私有推送密钥保存在原 VPS。

**首次部署。** 在自己的终端运行以下两条命令，通过浏览器登录 Cloudflare 并授权。首次设置账户的 Workers 子域名时，按 Cloudflare 提示选择名称。成功后用 Wrangler 实际输出的 HTTPS 地址邀请同学。

```powershell
npx.cmd --yes wrangler@4.158.0 login
npx.cmd --yes wrangler@4.158.0 deploy
```

发布前可用 `npx.cmd --yes wrangler@4.158.0 deploy --dry-run` 检查配置和打包。首次上线后从手机打开 `/version.json` 核对版本，在首页打开演示通知，再检查反馈和订阅。公开通知源尚未配置时，`/api/notices` 的 503 是预期结果。iPhone 接收手机提醒需要 iOS 16.4 以上，从 Safari 分享菜单添加到主屏幕，打开该域名的主屏幕图标，再由本人点“开启提醒”。订阅和本机阅读状态按域名隔离。[Wrangler 命令](https://developers.cloudflare.com/workers/wrangler/commands/workers/) · [WebKit 主屏幕推送](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

**更新与回退。** 每版重新生成部署包，用同一 Worker 名称运行 `deploy`。回退时进入 Cloudflare 面板的 Workers & Pages → campus-reader-safari → Deployments，选择已验证的上一版并点 Rollback。也可在已登录的终端运行 `npx.cmd --yes wrangler@4.158.0 rollback <版本ID>`。VPS 的订阅、反馈与通知数据继续由原服务管理。[回退文档](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/)

**代理范围。** Worker 只接受读取公开通知 / 推送配置，以及写入反馈、订阅、取消、测试的固定路径。写操作须来自 Worker 当前 origin，JSON 最多 4 KiB。Worker 用新请求访问固定 VPS，检查来路后为写操作设置 VPS 的 Origin，不复制浏览器请求头、Cookie、Authorization 或客户端 IP，也不跟随上游跳转。学校登录、门户正文抓取和管理员操作没有代理接口。部署后的平台网络元数据以 Cloudflare 实际运行环境为准。[Cloudflare HTTP Headers](https://developers.cloudflare.com/fundamentals/reference/http-headers/)
