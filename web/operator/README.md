# 维护者在 Windows 更新公开提醒

适用于一名维护者在自己的 Windows 电脑上访问校园门户。工具打开独立的临时 Edge 会话，由维护者在学校 HTTPS CAS 页面输入本人的学工号、密码并完成验证。先让电脑连上校园网或已获授权且覆盖 Edge 流量的校园通道。关闭窗口或退出工具后，下次运行需要重新登录。

在仓库根目录用 PowerShell 准备独立的 Python 环境（需本机 Edge 浏览器）。

~~~powershell
$envDir = "$env:LOCALAPPDATA\CampusReaderOperator\venv"
python -m venv $envDir
& "$envDir\Scripts\python.exe" -m pip install -r web/operator/requirements.txt
& "$envDir\Scripts\python.exe" web/operator/collect.py
~~~

1. 核对浏览器地址栏为学校的 HTTPS 认证站点，手动登录。终端回车后工具访问移动通知列表 1154，在窗口保留原列表供本人核对。脚本读取经过校验的编号、发布日期、原文地址，只在终端列出编号和日期。
2. 在终端逐个输入**确认可对匿名访问者公布提醒**的编号，以空格分隔。空行跳过此次导出。再次回车刷新最新页，可继续选择；输入 q 退出。若会话过期，按学校页面重新认证。未通过列表结构、日期、来源或编号检查时维持现有文件。
3. 默认输出在仓库外的 `%LOCALAPPDATA%\CampusReaderOperator\public-notices.json`，可用 `--output` 指定其他仓库外路径。每条只有稳定编号、日期、官方文章地址和固定文字“校园门户有新通知”，不采集详情和原文标题。再次选择同一编号只更新该条，既有提醒保留。输出上限 200 条。留在打开的终端可复用本次学校会话，工具不导出浏览器状态或凭据，不上传数据。
4. 审核 JSON 后把它部署到服务器专门的 feed 目录。SFTP 账号仅需该目录写权限，不能访问网页代码、反馈或 TLS 私钥。服务器的 `data` 目录须在静态站点目录之外，通过 Compose 只读挂载给 API；单一维护者维护同一本地文件，若从另一台电脑接手，先从 SFTP 取回之前的审核文件。已有非本工具格式的 feed 应独立管理，不会被合并覆盖。

例如在本机输出所在目录启动 `sftp 维护者账户@服务器`，核对主机指纹；进入管理员提供的远端目录，再执行：

~~~text
put public-notices.json .public-notices.next
rename .public-notices.next public-notices.json
~~~

需由服务端提供支持同目录原子替换的 SFTP（如 OpenSSH 的 POSIX rename 扩展）。若替换现有文件失败，保留旧文件和临时文件，联系管理员确认目录权限及原子 rename 支持。发布后从 HTTPS `/api/notices` 核对新增的编号；校园授权只发生在维护者本机，点击原文的读者是否能打开取决于学校权限。

可在本机运行 `python web/operator/test_collect.py` 进行 Edge 合成 DOM 检查。测试构造 1154 模板、重复刷新、失效会话、恶意链接与不合法日期，所有页面响应由本地测试夹具拦截。真实学校账号登录与服务器 SFTP 发布需维护者亲自验收。
