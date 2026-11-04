import SwiftUI
import CampusReaderCore

struct NoticeImage: View {
    let url: URL
    @ObservedObject var model: AppModel
    var thumbnail = false
    @State private var image: UIImage?
    @State private var zoom = false
    var body: some View {
        Group {
            if let image {
                Button { zoom = true } label: {
                    if thumbnail { Image(uiImage: image).resizable().scaledToFill().frame(width: 78, height: 78).clipped().clipShape(RoundedRectangle(cornerRadius: 10)) }
                    else { Image(uiImage: image).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12)) }
                }.buttonStyle(.plain).accessibilityLabel("查看通知图片")
            }
        }.task(id: url) {
            let owner = model.profileID
            if let data = try? await MediaStore.data(url: url, model: model), owner == model.profileID { image = UIImage(data: data) }
        }.sheet(isPresented: $zoom) {
            if let image { NavigationStack { ScrollView([.horizontal, .vertical]) { Image(uiImage: image).resizable().scaledToFit().frame(minWidth: 320) }.toolbar { ToolbarItem(placement: .confirmationAction) { Button("关闭") { zoom = false } }; ToolbarItem(placement: .bottomBar) { Button("保存 / 分享图片") { shareImage(image) } } } }
        }
    }
    private func shareImage(_ image: UIImage) {
        // The system share sheet provides Save Image without broad photo-library read access.
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              var root = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let presented = root.presentedViewController { root = presented }
        let sheet = UIActivityViewController(activityItems: [image], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = root.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: root.view.bounds.midX, y: root.view.bounds.maxY - 30, width: 1, height: 1)
        root.present(sheet, animated: true)
    }
}

struct SharedFile: Identifiable { let url: URL; var id: String { url.path } }
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct NoticeDetailView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var ai: AIService
    let notice: Notice
    @Environment(\.dismiss) private var dismiss
    @State private var file: SharedFile?
    @State private var error = ""
    @State private var downloading = false
    @State private var remind = false
    @State private var reminderDate = Date().addingTimeInterval(3600)
    @State private var editingTags = false
    @State private var tagText = ""
    private var current: Notice { model.library.notices.first(where: { $0.id == notice.id }) ?? notice }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(current.title).font(.title2.bold()).textSelection(.enabled)
                HStack { Text(current.publishedAt, style: .date); Spacer(); Text(current.isRead ? "已读" : "未读") }.font(.caption).foregroundStyle(.secondary)
                if let summary = ai.cached(current) { AISummaryCard(summary: summary) }
                if ai.configuration.enabled {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("AI 摘要", systemImage: "sparkles").font(.headline)
                        Text(ai.status).font(.footnote).foregroundStyle(.secondary)
                        Button("生成 / 重试摘要") {
                            let value = current
                            Task { do { _ = try await ai.summarize(value, policies: model.policies.passages(for: value)) } catch { self.error = error.localizedDescription } }
                        }.disabled(ai.isBusy || !current.hasBody)
                    }.padding().background(Color(.secondarySystemBackground)).clipShape(RoundedRectangle(cornerRadius: 14))
                }
                if !error.isEmpty { Text(error).font(.footnote).foregroundStyle(.red) }
                Text("通知原文").font(.headline)
                if current.hasBody { Text(current.bodyText).font(.body).lineSpacing(6).textSelection(.enabled) }
                else { Text("正文尚未缓存。联网后可重新加载，已读与稍后读状态保留。").foregroundStyle(.secondary); Button("重新加载正文") { Task { await model.ensureBody(current); if self.current.hasBody { model.read(notice.id, true) } } } }
                ForEach(current.images, id: \.self) { NoticeImage(url: $0, model: model) }
                if !current.attachments.isEmpty {
                    Text("附件").font(.headline)
                    ForEach(current.attachments) { attachment in
                        Button {
                            downloading = true
                            Task { defer { downloading = false }; do { file = SharedFile(url: try await MediaStore.export(attachment, model: model)) } catch { self.error = error.localizedDescription } }
                        } label: { Label(attachment.name, systemImage: "arrow.down.doc") }.disabled(downloading)
                    }
                    if downloading { ProgressView("正在下载附件…") }
                    Text("下载后可通过系统菜单保存到“文件”或分享。每个附件最多 20 MiB。").font(.caption).foregroundStyle(.secondary)
                }
                if current.originalURL.host == "my.bupt.edu.cn" {
                    NavigationLink { PortalOriginalView(service: model.portal, url: current.originalURL) } label: { Label("查看学校原文", systemImage: "link") }
                } else { Link("查看原文", destination: current.originalURL) }
            }.padding(22)
        }.navigationTitle("通知正文").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button(current.later ? "移出稍后读" : "加入稍后读") { model.later(current.id, !current.later) }
                        Button(current.isRead ? "标为未读" : "标为已读") { model.read(current.id, !current.isRead) }
                        Button("编辑标签") { tagText = current.tags.joined(separator: ", "); editingTags = true }
                        Button("设置时间提醒") { remind = true }
                    } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("通知操作")
                }
            }
            .task(id: notice.id) { await model.ensureBody(current); if current.hasBody { model.read(notice.id, true) }; model.enqueueSummaries() }
            .sheet(item: $file) { ShareSheet(items: [$0.url]) }
            .sheet(isPresented: $remind) {
                NavigationStack {
                    Form {
                        DatePicker("提醒时间", selection: $reminderDate, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                        Text("使用你选择的时间，不根据 AI 推测自动创建提醒。系统静音与专注模式仍然有效。").font(.footnote)
                        Button("保存提醒") { Task { do { try await ReaderNotifications.shared.remind(current, at: reminderDate, model: model); remind = false } catch { self.error = error.localizedDescription; remind = false } } }
                    }.navigationTitle("事项提醒").toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { remind = false } } }
                }.presentationDetents([.medium])
            }
            .alert("编辑标签", isPresented: $editingTags) { TextField("多个标签用逗号分隔", text: $tagText); Button("保存") { model.tags(current.id, tagText) }; Button("取消", role: .cancel) {} }
    }
}
