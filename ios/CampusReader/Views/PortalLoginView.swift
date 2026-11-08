import SwiftUI
import WebKit
import CampusReaderCore

struct PortalLoginView: View {
    @ObservedObject var service: PortalService
    let onAuthenticated: () -> Void
    var automaticallyRestore = true
    @State private var account = ""
    @State private var password = ""
    @State private var remember = true
    @State private var busy = false
    @State private var message = "校内优先直连；校外需要可用的校园访问通道。"
    @State private var launch: PortalWebLaunch?
    @State private var attemptedRestore = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "building.2.crop.circle.fill").font(.system(size: 48)).foregroundStyle(.blue)
                Text("北邮人信息").font(.largeTitle.bold())
                Text("登录信息门户，发现与你有关的校园消息").foregroundStyle(.secondary)
                TextField("学工号", text: $account).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled().textFieldStyle(.roundedBorder)
                SecureField("信息门户密码", text: $password).textContentType(.password).textFieldStyle(.roundedBorder)
                Toggle("保存密码并自动登录", isOn: $remember)
                Text("密码保存在这台 iPhone 的钥匙串中，仅提交至学校 HTTPS 认证页。校园门户正文与会话使用 HTTP。验证码需本人完成。").font(.footnote).foregroundStyle(.secondary)
                Button { Task { await begin() } } label: {
                    HStack { Spacer(); if busy { ProgressView().tint(.white) }; Text(busy ? "正在检查连接…" : "登录"); Spacer() }.padding(.vertical, 6)
                }.buttonStyle(.borderedProminent).disabled(busy || account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Text(message).font(.callout).foregroundStyle(.secondary).accessibilityIdentifier("portalLoginStatus")
                DisclosureGroup("校外访问说明") {
                    Text("此验证工程未包含厂商 VPN SDK。校外直连失败时，请先通过学校允许的系统 VPN / 官方客户端建立校园通道，再返回重试。通道可用后仍需门户账号登录；不会把 VPN 连接当作门户认证成功。").font(.footnote)
                }
                if !service.storageMessage.isEmpty { Text(service.storageMessage).font(.footnote).foregroundStyle(.orange) }
            }.padding(24)
        }
        .task {
            guard !attemptedRestore else { return }; attemptedRestore = true
            await service.restore(); account = service.activeAccount
            if service.hasAccount && automaticallyRestore { await begin(automatic: true) }
        }
        .sheet(item: $launch) { item in
            PortalWebLogin(service: service, launch: item) {
                launch = nil
                message = "门户连接成功"
                onAuthenticated()
            }
        }
        .onDisappear { password = "" }
    }
    private func begin(automatic: Bool = false) async {
        guard !busy else { return }; busy = true
        defer { busy = false }
        do {
            try await service.selectAccount(account)
            let owner = service.generation
            let route = await service.probe()
            guard owner == service.generation else { throw PortalError.cancelled }
            switch route {
            case .unavailable:
                password = ""
                message = "当前网络无法直连。请连接校园 Wi-Fi，或先建立学校允许的 VPN 通道后重试。"
                return
            case .rejected(let reason): message = reason; password = ""; return
            case .reachable: break
            }
            do { _ = try await service.validateSession(); password = ""; onAuthenticated(); return }
            catch PortalError.authenticationRequired { }
            catch {
                password = ""
                message = "门户页面尚未验证通过，请稍后重试。不会将解析或证书错误当作 VPN 故障。"
                return
            }
            var credential: PortalCredential?
            if automatic && !service.automaticRestoreAllowed { message = "自动登录已暂停或正在冷却，请检查密码后手动登录。"; return }
            if !password.isEmpty {
                guard password.count <= 1024 else { message = "密码过长，请检查输入"; password = ""; return }
                credential = PortalCredential(account: service.activeAccount, password: password, binding: "https://auth.bupt.edu.cn/authserver/login")
            } else {
                do { credential = try service.savedCredential() }
                catch let failure as KeychainStore.Failure {
                    message = failure.localizedDescription
                    if automatic { return }
                    // The web login can proceed without a stored password.
                }
            }
            password = ""
            if automatic && credential == nil { message = "门户需要重新登录，请输入密码。"; return }
            if automatic { service.noteAutomaticAttempt() }
            launch = PortalWebLaunch(credential: credential, remember: remember, owner: service.generation)
        } catch {
            password = ""
            message = "登录信息暂不可用，请解锁设备并检查学工号后重试。"
        }
    }
}

private struct PortalWebLaunch: Identifiable {
    let id = UUID()
    let credential: PortalCredential?
    let remember: Bool
    let owner: UInt64
}

private struct PortalWebLogin: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var service: PortalService
    let launch: PortalWebLaunch
    let onSuccess: () -> Void
    @State private var message = "正在打开学校认证页面…"
    @State private var verify = 0
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Text(message).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding()
                PortalAuthenticationWebView(service: service, launch: launch, status: $message, verify: verify, onSuccess: onSuccess)
            }.navigationTitle("登录信息门户").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("返回") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("检查登录") { verify += 1 } }
                }
        }.interactiveDismissDisabled(false)
    }
}

private struct PortalAuthenticationWebView: UIViewRepresentable {
    let service: PortalService
    let launch: PortalWebLaunch
    @Binding var status: String
    let verify: Int
    let onSuccess: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = service.websiteDataStore
        configuration.userContentController.add(context.coordinator, name: "portalUserEdited")
        configuration.userContentController.addUserScript(WKUserScript(source: "['input','click'].forEach(function(type){document.addEventListener(type,function(e){if(e.isTrusted)window.webkit.messageHandlers.portalUserEdited.postMessage(true)},true)})", injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.customUserAgent = PortalConfiguration.userAgent
        web.navigationDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = false
        web.isInspectableIfAvailable = false
        context.coordinator.web = web
        web.load(URLRequest(url: PortalConfiguration.login))
        return web
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.lastVerify != verify { context.coordinator.lastVerify = verify; context.coordinator.check() }
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.closed = true; coordinator.credential = nil; coordinator.task?.cancel(); view.stopLoading(); view.navigationDelegate = nil
        view.configuration.userContentController.removeScriptMessageHandler(forName: "portalUserEdited")
        // The school page holds the password only during its normal foreground login lifecycle.
        view.loadHTMLString("", baseURL: nil)
    }
    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: PortalAuthenticationWebView
        weak var web: WKWebView?
        var credential: PortalCredential?
        var submitted = false, closed = false, checking = false
        var lastVerify = 0
        var task: Task<Void, Never>?
        init(_ parent: PortalAuthenticationWebView) { self.parent = parent; credential = parent.launch.credential }
        private var live: Bool { !closed && parent.launch.owner == parent.service.generation }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            // A manual edit cannot authorize saving the earlier native password. The bridge
            // only revokes pending credentials; it never receives field values or cookies.
            if live, message.name == "portalUserEdited", message.frameInfo.isMainFrame { credential = nil }
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard live, let url = navigationAction.request.url, PortalConfiguration.web(url), navigationAction.targetFrame != nil else {
                parent.status = "已停止未知地址跳转，请返回后重试。"; decisionHandler(.cancel); return
            }
            if navigationAction.request.httpMethod?.uppercased() == "POST", !PortalConfiguration.authentication(url) {
                parent.status = "已拒绝向非认证页面提交登录信息。"; decisionHandler(.cancel); return
            }
            decisionHandler(.allow)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard live, let url = webView.url else { return }
            if PortalConfiguration.portal(url) { check(); return }
            guard PortalConfiguration.loginPage(url) else { parent.status = "请在学校页面完成额外验证。"; return }
            task = Task { @MainActor [weak self, weak webView] in
                guard let self, let webView, self.live else { return }
                do {
                    let result = try await webView.callAsyncJavaScript(PortalFormPolicy.probe, arguments: [:], in: nil, contentWorld: .page)
                    guard self.live, let form = result as? [String: Any], let page = form["page"] as? String,
                          let action = form["action"] as? String, PortalFormPolicy.accepts(form), webView.url?.absoluteString == page else {
                        self.credential = nil; self.parent.service.pauseAutomaticRestore(); self.parent.status = "此认证页面暂不支持自动填写。请返回后重试，或按学校提示完成验证。"; return
                    }
                    // Keep the selected account fixed while allowing password/captcha input.
                    _ = try await webView.callAsyncJavaScript(PortalFormPolicy.bindAccount, arguments: ["account": self.parent.service.activeAccount], in: nil, contentWorld: .page)
                    guard self.live else { return }
                    if (form["challenge"] as? Bool == true) || (form["error"] as? Bool == true) || self.submitted {
                        self.credential = nil; self.parent.service.pauseAutomaticRestore(); self.parent.status = "请在学校页面完成验证码或更正密码；本次手动输入不会保存。"; return
                    }
                    guard let credential = self.credential else { self.parent.status = "请在学校网页输入密码并登录。切换学工号请返回。"; return }
                    self.submitted = true
                    let submitted = try await webView.callAsyncJavaScript(PortalFormPolicy.submit, arguments: ["page":page,"action":action,"account":credential.account,"password":credential.password], in: nil, contentWorld: .page)
                    guard self.live else { return }
                    if submitted as? Bool != true { self.credential = nil; self.parent.status = "页面已变化，请在网页继续完成验证。" }
                    else { self.parent.status = "正在登录并检查通知访问…" }
                } catch {
                    // Successful submission can replace the JS context; didFinish validates the new page.
                    if !self.submitted { self.credential = nil; self.parent.status = "请在学校网页继续登录。" }
                }
            }
        }
        func check() {
            guard live, !checking else { return }; checking = true
            task = Task { @MainActor [weak self] in
                guard let self else { return }
                defer { self.checking = false }
                do {
                    try await self.parent.service.completeLogin(credential: self.submitted ? self.credential : nil, remember: self.parent.launch.remember, owner: self.parent.launch.owner)
                    guard self.live else { return }; self.credential = nil; self.parent.onSuccess()
                } catch {
                    guard self.live else { return }
                    self.parent.status = "门户尚未验证通过，请完成学校网页登录后再点“检查登录”。缓存不会清除。"
                }
            }
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { fail(error) }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { fail(error) }
        private func fail(_ error: Error) {
            guard live, (error as NSError).code != NSURLErrorCancelled else { return }
            credential = nil
            parent.status = "认证页加载失败，请检查网络后重试。证书校验保持开启。"
        }
    }
}

enum PortalFormPolicy {
    static func accepts(_ form: [String: Any]) -> Bool {
        guard let page = form["page"] as? String, let action = form["action"] as? String,
              let p = URL(string: page), let a = URL(string: action), PortalConfiguration.loginPage(p), PortalConfiguration.loginPage(a),
              ["loginForm","casLoginForm"].contains(form["id"] as? String ?? ""),
              (form["method"] as? String)?.lowercased() == "post", form["users"] as? Int == 1,
              form["passwords"] as? Int == 1, form["execution"] as? Bool == true, form["event"] as? Bool == true else { return false }
        return true
    }
    static let probe = """
    const f=document.querySelector('form#loginForm,form#casLoginForm'); if(!f)return {};
    const visible=e=>!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length);
    const extra=Array.from(f.querySelectorAll('input')).some(e=>visible(e)&&!['hidden','submit','button','checkbox','radio'].includes(e.type)&&!['username','password'].includes(e.name));
    const error=Array.from(document.querySelectorAll('.error,.errors,#msg,#errorMsg,.alert-danger')).some(e=>visible(e)&&e.textContent.trim().length>0);
    return {page:location.href,action:f.action,id:f.id,method:f.method,users:f.querySelectorAll('input[name=username]').length,passwords:f.querySelectorAll('input[name=password][type=password]').length,execution:!!f.querySelector('input[name=execution]'),event:!!f.querySelector('input[name=_eventId]'),challenge:extra,error:error};
    """
    static let bindAccount = """
    const f=document.querySelector('form#loginForm,form#casLoginForm');if(!f)return false;
    const u=f.querySelector('input[name=username]');if(!u)return false;u.value=account;u.readOnly=true;return true;
    """
    static let submit = """
    const f=document.querySelector('form#loginForm,form#casLoginForm');
    if(location.href!==page||!f||f.action!==action||f.method.toLowerCase()!=='post')return false;
    const u=f.querySelectorAll('input[name=username]'),p=f.querySelectorAll('input[name=password][type=password]');if(u.length!==1||p.length!==1)return false;
    const b=f.querySelector('button[type=submit],input[type=submit]');if(!b||b.hasAttribute('formaction')||b.disabled)return false;
    const extra=Array.from(f.querySelectorAll('input')).some(e=>!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)&&!['hidden','submit','button','checkbox','radio'].includes(e.type)&&!['username','password'].includes(e.name));if(extra)return false;
    u[0].value=account;p[0].value=password;[u[0],p[0]].forEach(e=>{e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))});b.click();return true;
    """
}

/// Original portal page uses the same account's WebKit cookie store, never Safari cookies.
struct PortalOriginalView: UIViewRepresentable {
    let service: PortalService
    let url: URL
    func makeCoordinator() -> Coordinator { Coordinator(owner: service.generation, service: service) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration(); configuration.websiteDataStore = service.websiteDataStore
        let view = WKWebView(frame: .zero, configuration: configuration); view.navigationDelegate = context.coordinator
        view.customUserAgent = PortalConfiguration.userAgent
        if PortalConfiguration.portal(url) { view.load(URLRequest(url: url)) }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        if context.coordinator.owner != service.generation { view.stopLoading(); view.loadHTMLString("", baseURL: nil) }
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) { view.stopLoading(); view.navigationDelegate = nil }
    @MainActor final class Coordinator: NSObject, WKNavigationDelegate {
        let owner: UInt64; let service: PortalService
        init(owner: UInt64, service: PortalService) { self.owner = owner; self.service = service }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // Expiry returns to the app login flow; arbitrary in-page login cannot change account.
            decisionHandler(owner == service.generation && action.targetFrame != nil && action.request.url.map(PortalConfiguration.portal) == true && action.request.httpMethod != "POST" ? .allow : .cancel)
        }
    }
}

private extension WKWebView {
    var isInspectableIfAvailable: Bool {
        get { if #available(iOS 16.4, *) { return isInspectable }; return false }
        set { if #available(iOS 16.4, *) { isInspectable = newValue } }
    }
}
