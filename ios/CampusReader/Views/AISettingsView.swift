import SwiftUI
import CampusReaderCore

struct AISettingsView: View {
    @ObservedObject var service: AIService
    @State private var draft = SummaryConfiguration.default
    @State private var key = ""
    @State private var message = ""
    @State private var confirmClear = false
    var body: some View {
        Form {
            Section("摘要服务") {
                Picker("服务类型", selection: $draft.provider) {
                    Text("DeepSeek 官方").tag(AIProvider.deepSeek)
                    Text("兼容接口").tag(AIProvider.compatible)
                }.onChange(of: draft.provider) { value in
                    if value == .deepSeek { draft.endpoint = SummaryConfiguration.default.endpoint; draft.model = SummaryConfiguration.default.model }
                    key = ""
                }
                TextField("HTTPS 接口（含 /chat/completions）", text: $draft.endpoint)
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("模型名称", text: $draft.model).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("API Key（留空保留同接口密钥）", text: $key).textContentType(.password).textInputAutocapitalization(.never).autocorrectionDisabled()
                Text("密钥只存于本机钥匙串，不随源码或安装包分发。更换接口需重新填写；自定义服务只接受 HTTPS。").font(.caption).foregroundStyle(.secondary)
            }
            Section("发送授权") {
                Toggle("启用 AI 摘要", isOn: $draft.enabled)
                Toggle("自动摘要当前页与新通知", isOn: $draft.automatic).disabled(!draft.enabled)
                Toggle("允许发送相关政策片段", isOn: $draft.sharePolicies).disabled(!draft.enabled)
                Text("启用后，通知标题与有限正文会发送给你配置的服务，可能计费。政策片段需单独授权；图片、附件、校园密码和个人兴趣不会发送。自动任务失败后不盲目重试。").font(.caption).foregroundStyle(.secondary)
                Stepper("每日最多尝试 \(draft.dailyLimit) 次", value: $draft.dailyLimit, in: 1...100)
                Text("失败或取消也计入次数。修改上限、清摘要不会退还已经使用的次数。后台执行由 iOS 安排。").font(.caption).foregroundStyle(.secondary)
            }
            Section {
                Button(draft.automatic && draft.enabled ? "保存并开启自动摘要" : "保存设置") {
                    do {
                        if !draft.enabled { draft.automatic = false }
                        try service.configure(draft, key: key.isEmpty ? nil : key)
                        key = ""; draft = service.configuration; message = service.status
                    } catch { message = error.localizedDescription }
                }.buttonStyle(.borderedProminent)
                if !message.isEmpty { Text(message).font(.footnote).foregroundStyle(.secondary) }
                Text(service.status).font(.footnote).foregroundStyle(.secondary)
            }
            Section("本机管理") {
                Button("清除摘要缓存", role: .destructive) { confirmClear = true }
                Button("删除当前服务密钥并停用 AI", role: .destructive) { service.clearCredentials(); draft = service.configuration; key = "" }
            }
        }.navigationTitle("AI 摘要")
            .onAppear { draft = service.configuration }
            .onDisappear { key = "" }
            .confirmationDialog("清除已有摘要？阅读状态与标签保留，自动尝试记录和次数不清零。", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("清除摘要", role: .destructive) { service.clearCache() }
            }
    }
}
