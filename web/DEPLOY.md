# 北邮人信息 Safari 内测部署

同学用 Safari 打开 HTTPS 地址，即可测试通知列表、正文和返回操作。首轮使用明确标记的演示内容。学校的认证及受限通知由学生本人在官方门户查看。公开站点无法复用另一来源的 Safari 登录状态，门户登录并不会自动把数据导入阅读页。

## 在 Actions 构建

仓库根目录运行：

~~~sh
python web/build.py --output dist/safari
~~~

`dist/safari/site/` 可单独发布至 Pages 的项目子路径；反馈入口指向公开的 GitHub Issues。
`dist/safari/campus-reader-safari-server.zip` 是服务器部署包，包含站点、接口、HTTPS 配置、探测脚本和文件校验清单。两者各自的 `version.json` 写入同一版本号和 Git 提交，以及构建时 Web 源码是否有未提交改动。ZIP 内的网页启用同源反馈 API。

## 在服务器上线

以下示例假定已有 Linux、Docker Engine、Compose v2 和 `unzip`。服务器只提供文件及反馈接口，不编译 App 或安装 npm 包。部署需要一个解析至服务器的域名、入站 80/443 和访问镜像仓库、证书签发服务的网络。若 80/443 已由现有反向代理管理，先配置该代理的子域名和证书，再将同样的站点/API 路由接入已有服务。

1. 把 Actions 中的 ZIP 上传到服务器；使用新的独立版本目录解包，确认文件校验。

   ~~~sh
   mkdir -p ~/campus-reader/releases/beta1
   cd ~/campus-reader/releases/beta1
   unzip /path/to/campus-reader-safari-server.zip
   sha256sum -c SHA256SUMS
   ~~~

2. 指定域名（用你的真实域名替换下面的示例）并启动：

   ~~~sh
   export CAMPUS_DOMAIN=reader.example.org
   docker compose config --quiet
   docker compose up -d
   docker compose ps
   curl --fail "https://${CAMPUS_DOMAIN}/version.json"
   ~~~

   Caddy 自动申请 HTTPS 证书并续期。API 容器通过 Compose 内部网络接收请求，服务器只对外开放网页的 HTTP/HTTPS 端口。

3. 在这台服务器上探测校园连接；保存结果时注明运行位置，切勿用开发电脑的结果代表服务器。

   ~~~sh
   python3 probe_portal.py
   ~~~

   两个预设地址分别检测门户主页和通知列表。探测不使用用户密码、Cookie 或环境代理；只输出主机、状态码、分类和可确认的通知链接数量，不保存页面、查询参数或会话。看到 `authentication_required` 说明到达了认证边界；`reachable_unverified_page` 仅说明返回了网页；`anonymous_notice_links` 仅表示当前运行位置看到了列表链接，不能据此将内容公开。

4. 用 Safari 打开 HTTPS 地址，核对版本、打开详情并返回、测试稍后读，提交仅含测试现象的一句话反馈。可通过 Safari 分享菜单“添加到主屏幕”。反馈在 2 核 2 GB 服务器的私有卷中保存；管理员在服务器上执行 `docker compose exec -T api cat /state/feedback.ndjson` 查看。反馈内容、时间和软件版本会保存；接口不提供公开读取操作。不在公开 issue 或反馈中填写学号、密码、模型 Key 或受限通知原文。

## 可选的公开数据

若维护者取得可向任何匿名访问者公开的通知，可在部署目录放置 `data/public-notices.json`。更新时将新文件写完并原子替换，接口会重新读取。仅使用示例或明确可公开的数据；真实受限通知应在学生自己的手机与门户之间访问。

~~~json
{
  "visibility": "public",
  "notices": [
    {
      "id": "demo-1",
      "title": "合成示例：开放日",
      "publishedAt": "2026-11-08T09:00:00+08:00",
      "body": "这是演示内容。"
    }
  ]
}
~~~

每条通知有稳定且唯一的 `id`、标题、正文、时间，可选原文地址 `url` 仅接受门户主机。服务最多接受 200 条及 512 KiB 的文件。没有配置时，`GET /api/notices` 返回 503 与 `public_feed_not_configured`；配置或数据格式有误时返回 503 与 `invalid_public_feed`。不要把文件复制到 `site/` 下。

服务默认接收本站网页的小型 JSON 反馈，有请求大小、并发、每日、每月和总文件容量限制。换域名时同步修改 `CAMPUS_DOMAIN`。公开 GitHub Pages 上的反馈使用 GitHub 账户与 Issues，由用户自己决定是否公开提交。

如果同一站点使用另一个受控 HTTPS 网页域名（例如 Cloudflare Worker），可显式设置 `PUBLIC_CLIENT_ORIGINS` 为该网页的 origin；例如 `https://reader.example.workers.dev`，不含路径或末尾斜杠。多个维护者所控制的域名可用逗号分隔。使用 Compose 时设置环境变量后重新运行 `docker compose up -d`；使用 systemd 时更新 API 服务环境并重启服务。这些网页可以由浏览器直接调用 VPS 六个公开接口；每个接口仅允许其指定方法，写入需要 JSON。CORS 仅反映白名单中实际收到的 origin，不使用通配或 Cookie 凭据。公开站点无需为访问者增加账号。移除某个前端时，请同时将其从白名单删除。

## 服务器版公开通知提醒

无需校园账号。iPhone 用户使用支持 Web Push 的系统（iOS 16.4 及以上），将 HTTPS 网页添加到主屏幕，从该图标打开，点击页面上的订阅按钮并允许系统通知。GitHub Pages 静态版本没有推送服务接口。[WebKit 官方说明](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

服务器首次启动在私有状态目录生成 VAPID 密钥。Compose 使用持久卷 `/state`，systemd 使用 `/var/lib/campus-reader-safari-api`。严禁托管或提交 `push-state.json` 及临时副本；它们包含 VAPID 私钥、设备推送地址和管理凭证摘要。备份按私有凭据保存，更新或回退代码时继续使用同一目录。公钥可以从 `GET /api/push/config` 取得。私有状态损坏时推送 API 返回 503；先检查目录、权限及备份，避免静默换钥匙使所有设备失效。

订阅/取消/测试需要来自批准网页的 JSON 请求及该安装随机生成的管理 token，不提供匿名广播接口。测试提醒只有推送服务接受后返回 `accepted:true`；本人还需在设备上确认收到提醒，点开后回到当前站点。推送消息没有正文或个人数据，服务只对接固定的 Apple、Chrome 和 Firefox 推送主机，拒绝跳转到其他地址。网关 404 或 410 的订阅会自动清理。每个设备两次测试至少间隔一分钟，24 小时最多 4 次，Beta 服务最多 64 个订阅。

投放一条新的公开通知时，按上一节审核并原子替换 `public-notices.json`。服务每分钟检查一次，按稳定 ID 去重，同批新增合并为一次通用提醒。首次启用时已有通知只建立基线。若需立即检查公开 feed，维护者可执行 `docker compose kill -s SIGUSR2 api`，systemd 部署则使用 `systemctl kill --kill-whom=main --signal=SIGUSR2 campus-reader-safari-api`。服务日志只显示各推送结果的数量。两次群发至少相隔两分钟。

每个当时已订阅的设备最多有一条持久待发提醒；后续新通知合并。网络超时、网关 429 或 5xx 时仅重试尚未成功的设备；退避 1 分钟、5 分钟、30 分钟、2 小时，尊重限流服务返回的 `Retry-After`，单批至多尝试 5 次，最长 24 小时。服务重启继续剩余任务，网关接受或永久拒绝后清理；退订会删除对应待发任务，之后加入的设备不补收旧批次。网关已接收但服务在保存回执前意外中断时，重试可能重复。最多保留 20,000 个已处理 ID 的摘要，达到容量时需维护者安排账本迁移。

## 更新与回退

将下一版 ZIP 解压至新的版本目录，沿用相同域名与 Compose 项目名，从新目录运行 `docker compose up -d --force-recreate`。核对 HTTPS `version.json` 的提交 ID。需要回退时在保留的旧版本目录执行同一条启动命令并核对版本；沿用现有证书卷与反馈卷。若启用了公开 feed，将同一份经审核的数据放在对应版本目录的 `data/` 下。GitHub Pages 版本则对目标提交重跑发布流程并以公开地址的 `version.json` 核对生效版本。
