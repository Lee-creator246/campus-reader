import SwiftUI
import CampusReaderCore

private enum ReaderDestination: String, Identifiable {
    case interests, settings, ai, policies, history, subscriptions, feedback
    var id: String { rawValue }
}

struct ReaderShell: View {
    @ObservedObject var model: AppModel
    @ObservedObject var portal: PortalService
    @State private var sidebar = false
    @State private var destination: ReaderDestination?
    var body: some View {
        Group {
            if !model.ready { ProgressView("正在准备本机资料…") }
            else if (model.profileID.isEmpty || model.profileID != portal.profileID) && !model.previewMode {
                if model.showLogin { Text("请完成门户账号登录，已有阅读资料保留。") }
                else { PortalLoginView(service: portal, onAuthenticated: model.authenticated) }
            }
            else {
                NavigationStack {
                    ReaderListView(model: model, ai: model.ai)
                        .navigationTitle("北邮人信息").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .navigationBarLeading) { Button { sidebar = true } label: { Image(systemName: "line.3.horizontal") }.accessibilityLabel("打开侧栏") } }
                }
            }
        }
        .tint(Color(red: 0.13, green: 0.33, blue: 0.68))
        .sheet(isPresented: $sidebar) {
            NavigationStack {
                List {
                    Section {
                        Label("与你有关的校园信息", systemImage: "newspaper")
                        Button("全部通知 · \(model.unreadCount) 条未读") { model.source = nil; model.tag = nil; model.topic = nil; model.mode = .latest; sidebar = false }
                        Button("稍后读") { model.mode = .later; sidebar = false }
                    }
                    Section("个人阅读") {
                        destinationButton("关注偏好与屏蔽词", icon: "slider.horizontal.3", value: .interests)
                        destinationButton("AI 摘要服务", icon: "sparkles", value: .ai)
                        destinationButton("政策资料库", icon: "doc.text.magnifyingglass", value: .policies)
                        destinationButton("订阅管理", icon: "dot.radiowaves.left.and.right", value: .subscriptions)
                    }
                    if !model.allTags.isEmpty {
                        Section("我的标签") { ForEach(model.allTags, id: \.self) { tag in Button(tag) { model.tag = tag; model.topic = nil; sidebar = false } } }
                    }
                    Section("连接与设置") {
                        Button { sidebar = false; model.showLogin = true } label: { Label("门户账号与连接", systemImage: "person.crop.circle") }
                        destinationButton("刷新与提醒记录", icon: "clock.arrow.circlepath", value: .history)
                        destinationButton("阅读与通知设置", icon: "gearshape", value: .settings)
                        destinationButton("体验反馈", icon: "bubble.left", value: .feedback)
                    }
                }.navigationTitle("北邮人信息")
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { sidebar = false } } }
            }
        }
        .sheet(item: $destination, onDismiss: { model.enqueueSummaries() }) { item in
            NavigationStack {
                Group {
                    switch item {
                    case .interests: InterestsView(model: model)
                    case .settings: ReaderSettingsView(model: model)
                    case .ai: AISettingsView(service: model.ai)
                    case .policies: PoliciesView(store: model.policies)
                    case .history: HistoryView(model: model)
                    case .subscriptions: SubscriptionsView(model: model)
                    case .feedback: FeedbackView()
                    }
                }.toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { destination = nil } } }
            }
        }
        .sheet(isPresented: $model.showLogin) { PortalLoginView(service: portal, onAuthenticated: model.authenticated) }
        .sheet(isPresented: $model.showInterests) { NavigationStack { InterestsView(model: model) } }
        .sheet(item: $model.selectedNotice) { notice in
            NavigationStack { NoticeDetailView(model: model, ai: model.ai, notice: notice) }
        }
        .onChange(of: model.profileID) { _ in destination = nil; sidebar = false }
        .onChange(of: portal.profileID) { _ in model.accountSelectionChanged() }
    }
    private func destinationButton(_ title: String, icon: String, value: ReaderDestination) -> some View {
        Button { sidebar = false; DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { destination = value } } label: { Label(title, systemImage: icon) }
    }
}

struct ReaderListView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var ai: AIService
    @State private var preview: Notice?
    @State private var tagNotice: Notice?
    @State private var tagText = ""
    var body: some View {
        VStack(spacing: 0) {
            Picker("阅读范围", selection: $model.mode) {
                Text("推荐").tag(ReaderMode.recommended); Text("最新").tag(ReaderMode.latest)
                Text("未读").tag(ReaderMode.unread); Text("稍后读").tag(ReaderMode.later)
            }.pickerStyle(.segmented).padding(.horizontal).padding(.vertical, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("全部标签", selected: model.topic == nil && model.tag == nil) { model.topic = nil; model.tag = nil }
                    ForEach(Recommendation.allTopics, id: \.self) { topic in chip(topic, selected: model.topic == topic) { model.topic = topic; model.tag = nil } }
                    ForEach(model.allTags, id: \.self) { tag in chip("#" + tag, selected: model.tag == tag) { model.tag = tag; model.topic = nil } }
                }.padding(.horizontal).padding(.bottom, 10)
            }
            List {
                if !model.status.isEmpty {
                    Text(model.status).font(.footnote).foregroundStyle(.secondary).listRowSeparator(.hidden)
                }
                if model.portal.sessionState == .loginRequired {
                    Button("门户会话已过期 · 重新登录") { model.showLogin = true }
                }
                if model.notices.isEmpty {
                    VStack(spacing: 12) { Image(systemName: "tray").font(.largeTitle); Text("暂时没有匹配的通知"); Text("可刷新列表，或调整标签与屏蔽规则。已缓存的内容不会因登录过期而删除。").font(.footnote) }.foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.vertical, 40)
                }
                ForEach(model.notices) { notice in
                    Button { model.selectedNotice = notice } label: {
                        HStack(alignment: .top, spacing: 12) {
                            if let url = notice.images.first { NoticeImage(url: url, model: model, thumbnail: true) }
                            VStack(alignment: .leading, spacing: 8) {
                                Text(notice.title).font(.system(.body, design: .default).weight(notice.isRead ? .regular : .semibold)).foregroundStyle(notice.isRead ? Color.secondary : Color.primary).multilineTextAlignment(.leading)
                                if let summary = ai.cached(notice) { Text(summary.overview.text).font(.subheadline).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.leading) }
                                if model.mode == .recommended, let reason = Recommendation.reason(notice, preferences: model.settings.preferences) { Text(reason).font(.caption).foregroundStyle(Color.accentColor) }
                                HStack(spacing: 8) {
                                    Text(notice.publishedAt, style: .date)
                                    if notice.later { Image(systemName: "bookmark.fill") }
                                    if !notice.tags.isEmpty { Text(notice.tags.map { "#" + $0 }.joined(separator: " ")).lineLimit(1) }
                                }.font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 8).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                        .accessibilityIdentifier("notice-" + notice.id)
                        .accessibilityLabel(notice.title + (notice.isRead ? "，已读" : "，未读"))
                        .contextMenu {
                            Button { preview = notice } label: { Label("预览摘要", systemImage: "text.bubble") }
                            Button { model.later(notice.id, !notice.later) } label: { Label(notice.later ? "移出稍后读" : "加入稍后读", systemImage: "bookmark") }
                            Button { model.read(notice.id, !notice.isRead) } label: { Label(notice.isRead ? "标为未读" : "标为已读", systemImage: "envelope") }
                            Button { tagText = notice.tags.joined(separator: ", "); tagNotice = notice } label: { Label("编辑标签", systemImage: "tag") }
                            Button { model.feedback(notice, more: true) } label: { Label("多推荐此类", systemImage: "hand.thumbsup") }
                            Button { model.feedback(notice, more: false) } label: { Label("不感兴趣", systemImage: "hand.thumbsdown") }
                        } preview: {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(notice.title).font(.headline)
                                Text(ai.cached(notice)?.overview.text ?? "暂无已生成摘要。配置并开启自动摘要后，可在这里预览。")
                            }.padding(24).frame(width: 320)
                        }
                }
                if model.nextPage != nil {
                    Button { Task { await model.refresh(more: true) } } label: { HStack { Spacer(); if model.refreshing { ProgressView() } else { Text("加载更多") }; Spacer() } }.disabled(model.refreshing)
                }
                Text("\(model.notices.count) 条匹配 · 阅读状态保存在本机").font(.caption).foregroundStyle(.secondary).listRowSeparator(.hidden)
            }.listStyle(.plain).refreshable { await model.refresh() }
        }.searchable(text: $model.query, prompt: "搜索标题与已缓存正文")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button { Task { await model.refresh() } } label: { Image(systemName: "arrow.clockwise") }.disabled(model.refreshing).accessibilityLabel("刷新通知") } }
            .sheet(item: $preview) { notice in NavigationStack { NoticePreviewView(model: model, ai: ai, notice: notice) } }
            .alert("编辑标签", isPresented: Binding(get: { tagNotice != nil }, set: { if !$0 { tagNotice = nil } })) {
                TextField("多个标签用逗号分隔", text: $tagText)
                Button("保存") { if let notice = tagNotice { model.tags(notice.id, tagText) }; tagNotice = nil }
                Button("取消", role: .cancel) { tagNotice = nil }
            }
            .onChange(of: model.mode) { _ in model.enqueueSummaries() }
            .onChange(of: model.topic) { _ in model.enqueueSummaries() }
    }
    private func chip(_ text: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(text).font(.subheadline).padding(.horizontal, 14).frame(minHeight: 40).background(selected ? Color.accentColor.opacity(0.12) : Color(.secondarySystemBackground)).foregroundStyle(selected ? Color.accentColor : .secondary).clipShape(Capsule()) }.buttonStyle(.plain)
    }
}

struct NoticePreviewView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var ai: AIService
    let notice: Notice
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(notice.title).font(.title2.bold())
                if let summary = ai.cached(notice) { AISummaryCard(summary: summary) }
                else { Text("暂无摘要。开启自动摘要后，当前页与新通知会按额度逐步生成。预览不会将通知标为已读。").foregroundStyle(.secondary) }
                Button("打开正文") { dismiss(); DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { model.selectedNotice = notice } }.buttonStyle(.borderedProminent)
            }.padding(24)
        }.navigationTitle("摘要预览").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("关闭预览") { dismiss() } } }
    }
}
