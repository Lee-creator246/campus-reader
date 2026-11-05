import SwiftUI
import UniformTypeIdentifiers
import CampusReaderCore

struct InterestsView: View {
    @ObservedObject var model: AppModel
    @State private var keywords = ""
    @State private var blocked = ""
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Form {
            Section { Text("选择你关注的主题，也可以跳过。偏好仅保存在本机，不发送给 AI。已读状态不会被当作兴趣。").font(.subheadline) }
            Section("关注主题") {
                ForEach(Recommendation.allTopics, id: \.self) { topic in
                    Toggle(topic, isOn: Binding(get: { model.settings.preferences.topics.contains(topic) }, set: { selected in
                        model.settings.preferences.topics.removeAll { $0 == topic }; if selected { model.settings.preferences.topics.append(topic) }
                    }))
                }
                Toggle("关注主题优先提醒", isOn: $model.settings.preferences.themeAlerts)
            }
            Section("优先关注关键词") { TextEditor(text: $keywords).frame(minHeight: 85); Text("例如：志愿、竞赛、奖学金。用逗号或换行分隔，最多 100 个词。").font(.caption).foregroundStyle(.secondary) }
            Section("屏蔽词") { TextEditor(text: $blocked).frame(minHeight: 85); Text("例如：任职、任免。命中后不进入列表、推送或自动摘要。稍后读与标签不会覆盖屏蔽规则。").font(.caption).foregroundStyle(.secondary) }
            Section("可选偏好") {
                Picker("适用对象", selection: $model.settings.preferences.audience) { Text("暂不选择").tag(""); Text("本科生").tag("本科生"); Text("研究生").tag("研究生"); Text("教职工").tag("教职工") }
                Toggle("使用我的“多推荐 / 不感兴趣”反馈", isOn: $model.settings.preferences.learningEnabled)
                Button("重置本机推荐反馈") { model.settings.preferences.feedback = [:] }
            }
            Section {
                Button("保存偏好") {
                    model.settings.preferences.keywords = terms(keywords); model.settings.preferences.blocked = terms(blocked)
                    model.finishInterests(); dismiss()
                }.accessibilityIdentifier("save-interests")
                Button("稍后设置") { model.finishInterests(); dismiss() }
            }
        }.navigationTitle("关注偏好").onAppear { keywords = model.settings.preferences.keywords.joined(separator: "\n"); blocked = model.settings.preferences.blocked.joined(separator: "\n") }
    }
    private func terms(_ input: String) -> [String] { Array(Set(input.components(separatedBy: CharacterSet(charactersIn: ",，\n")).map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60)) }.filter { !$0.isEmpty })).sorted().prefix(100).map { $0 } }
}

struct ReaderSettingsView: View {
    @ObservedObject var model: AppModel
    let onManageAccount: () -> Void
    @State private var permission = ""
    var body: some View {
        Form {
            Section("刷新与通知") {
                Toggle("打开时刷新", isOn: $model.settings.refreshOnOpen)
                Toggle("允许系统安排后台刷新", isOn: $model.settings.backgroundRefresh)
                Picker("期望检查间隔", selection: $model.settings.intervalMinutes) { ForEach([15,30,60,120,240], id: \.self) { Text("\($0) 分钟").tag($0) } }
                Text("iOS 决定实际运行时间；低电量、关闭后台刷新、强制退出或门户过期都会影响检查。这里的间隔不是准点推送保证。").font(.footnote).foregroundStyle(.secondary)
                Toggle("新通知提醒", isOn: $model.settings.notifications)
                Button("允许系统通知") { Task { permission = await ReaderNotifications.shared.requestAuthorization() ? "系统通知已允许" : "未获允许，可到系统设置调整" } }
                Button("打开系统设置") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                if !permission.isEmpty { Text(permission).font(.caption) }
            }
            Section("本机缓存") {
                Stepper("正文最多 \(model.settings.retention.maximumBodies) 条", value: $model.settings.retention.maximumBodies, in: 100...1000, step: 100)
                Picker("正文容量", selection: $model.settings.retention.maximumBodyBytes) { ForEach([10,20,40,80], id: \.self) { Text("\($0) MiB").tag($0 * 1_024 * 1_024) } }
                Picker("已读正文清理", selection: $model.settings.retention.readBodyHours) { Text("24 小时后").tag(24.0); Text("3 天后").tag(72.0); Text("7 天后").tag(168.0); Text("不按已读时间清理").tag(-1.0) }
                Text("稍后读与带标签正文优先保留，仍受总容量约束。图片与附件缓存合计最多 40 MiB；阅读状态独立保留。").font(.footnote).foregroundStyle(.secondary)
                Button("按当前规则清理缓存") { model.cleanCache() }
            }
            Section("账号") {
                Button("重新登录信息门户", action: onManageAccount)
                Button("删除已保存门户密码") { do { try model.portal.forgetSavedPassword(); model.status = "已删除本机保存的门户密码" } catch { model.status = "删除失败，请解锁设备后重试。" } }
                Button("退出当前账号", role: .destructive) { Task { await model.logout() } }
                Text("密码与 API Key 存在本机钥匙串；门户和正文使用学校现有 HTTP 地址。校园账号不会发送给 AI。退出保留本机阅读文件，下次同一账号登录可继续使用。").font(.footnote).foregroundStyle(.secondary)
            }
            Button("保存设置") { model.saveSettings() }
            Text(model.status).font(.caption).foregroundStyle(.secondary)
        }.navigationTitle("阅读与通知设置")
    }
}

struct HistoryView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        List {
            Text("仅保留本机最近 14 天、最多 200 条刷新记录。成功刷新不等同于系统一定显示了通知。").font(.footnote).foregroundStyle(.secondary)
            ForEach(model.history) { item in
                VStack(alignment: .leading, spacing: 6) { Text(item.message).font(.headline); Text(item.date, style: .time); Text("\(item.background ? "后台" : "前台") · 更新 \(item.saved) · 新增 \(item.inserted) · 未完成 \(item.failed)").font(.caption) }
            }
        }.navigationTitle("刷新记录")
    }
}

struct PoliciesView: View {
    @ObservedObject var store: PolicyLibraryStore
    @State private var importing = false
    @State private var publisher = ""
    @State private var version = ""
    @State private var error = ""
    var body: some View {
        Form {
            Section("导入校内政策") {
                TextField("发布部门（选填）", text: $publisher)
                TextField("版本 / 发布日期（选填）", text: $version)
                Button("从文件导入 TXT / PDF") { importing = true }
                Text("最多 20 份。文字仅在本机检索，只有在 AI 设置中另外允许发送政策片段后，相关片段才会随摘要请求发送。引用不代表自动认定政策有效性或适用资格。").font(.caption).foregroundStyle(.secondary)
            }
            if !error.isEmpty { Text(error).foregroundStyle(.red) }
            if let message = store.error { Text(message).foregroundStyle(.red) }
            Section("已导入") {
                ForEach(store.documents) { document in
                    NavigationLink {
                        ScrollView { VStack(alignment: .leading, spacing: 16) { Text(document.title).font(.title2); ForEach(Array(document.pages.enumerated()), id: \.offset) { index, text in Text("第 \(index + 1) 页").font(.headline); Text(text).textSelection(.enabled) } }.padding() }
                    } label: { VStack(alignment: .leading) { Text(document.title); Text(document.publisher + " " + document.version).font(.caption).foregroundStyle(.secondary) } }
                }.onDelete { offsets in let ids = offsets.map { store.documents[$0].id }; for id in ids { do { try store.remove(id) } catch { self.error = error.localizedDescription } } }
            }
        }.navigationTitle("政策资料库")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .pdf]) { result in
                Task { do { try await store.importDocument(result.get(), publisher: publisher, version: version) } catch { self.error = error.localizedDescription } }
            }
    }
}

struct SubscriptionsView: View {
    @ObservedObject var model: AppModel
    @State private var url = ""
    @State private var loading = false
    var body: some View {
        Form {
            Section("RSS / Atom") { TextField("HTTPS 订阅地址", text: $url).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL); Button("添加或刷新订阅") { loading = true; Task { await model.addFeed(url); loading = false } }.disabled(loading); if loading { ProgressView() }; Text(model.status).font(.caption) }
            Section("已订阅") {
                ForEach(model.library.subscriptions) { subscription in
                    VStack(alignment: .leading) { Text(subscription.title); Text(subscription.url.absoluteString).font(.caption).foregroundStyle(.secondary); Button("刷新") { Task { await model.addFeed(subscription.url.absoluteString) } } }
                }.onDelete { indexes in let ids = indexes.map { model.library.subscriptions[$0].id }; for id in ids { model.removeFeed(id) } }
            }
            Text("外部订阅采用独立匿名连接，不携带校园 Cookie。订阅内容与校园通知使用同一套阅读状态、标签和屏蔽规则。").font(.footnote).foregroundStyle(.secondary)
        }.navigationTitle("订阅管理")
    }
}

struct FeedbackView: View {
    @State private var score = 4
    @State private var comment = ""
    @State private var preview = false
    @State private var sharing = false
    private var answer: String { "北邮人信息 iOS 体验反馈\n体验评分：\(score)/5\n建议：\(comment)\n自愿提供，仅用于改善使用体验。" }
    var body: some View {
        Form {
            Text("问卷自愿填写，仅用于优化体验。内容暂存在当前页面，预览后由你主动分享。不会附带学号、密码、API Key、设备标识或阅读记录，也没有自动上传服务器。").font(.subheadline)
            Stepper("体验评分 \(score) / 5", value: $score, in: 1...5)
            TextEditor(text: $comment).frame(minHeight: 160).onChange(of: comment) { value in if value.count > 2000 { comment = String(value.prefix(2000)) } }
            Text("请勿在反馈中填写密码、验证码或其他个人敏感信息。").font(.caption).foregroundStyle(.secondary)
            Button("预览反馈") { preview = true }
            if preview { Text(answer).textSelection(.enabled); Button("自行分享反馈") { sharing = true } }
        }.navigationTitle("体验反馈").sheet(isPresented: $sharing) { ShareSheet(items: [answer]) }
    }
}
