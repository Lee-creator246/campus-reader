import SwiftUI
import CampusReaderCore

struct AISummaryCard: View {
    let summary: CitedSummary
    @State private var evidence: SummaryCitation?
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("AI 摘要", systemImage: "sparkles").font(.subheadline.weight(.semibold)).foregroundStyle(.blue)
            claim(summary.overview)
            ForEach(Array(summary.points.enumerated()), id: \.offset) { _, point in
                VStack(alignment: .leading, spacing: 3) {
                    Text(point.kind).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    claim(point)
                }
            }
            if !summary.tags.isEmpty { Text(summary.tags.map { "#" + $0 }.joined(separator: "  ")).font(.caption).foregroundStyle(.secondary) }
            if !summary.policyLinks.isEmpty {
                DisclosureGroup("相关政策参考（需自行核对适用性）") {
                    ForEach(summary.policyLinks) { link in
                        Button { evidence = link.policy } label: {
                            Label(link.policy.documentTitle + " · " + link.policy.location, systemImage: "doc.text")
                                .font(.footnote).multilineTextAlignment(.leading)
                        }.padding(.vertical, 4)
                    }
                }.font(.footnote)
            }
            Text("摘要可能遗漏条件；重要事项请点数字核对原文。").font(.caption2).foregroundStyle(.secondary)
        }
        .padding(16).background(Color.blue.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
        .sheet(item: $evidence) { citation in
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(citation.documentTitle).font(.headline)
                        Text(citation.location).font(.caption).foregroundStyle(.secondary)
                        Text(citation.quote).font(.body.weight(.medium)).padding(12).background(Color.yellow.opacity(0.14), in: RoundedRectangle(cornerRadius: 10))
                        Text("所在原文片段").font(.subheadline.weight(.semibold))
                        Text(citation.sourceText).textSelection(.enabled)
                    }.frame(maxWidth: .infinity, alignment: .leading).padding()
                }.navigationTitle("原文依据").navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { evidence = nil } } }
            }
        }
    }
    private func claim(_ claim: SummaryClaim) -> some View {
        let units = claim.citations
        return Text(attributed(claim))
            .font(.subheadline).textSelection(.enabled)
            .environment(\.openURL, OpenURLAction { url in
                guard url.scheme == "campus-evidence", let index = Int(url.host ?? ""), units.indices.contains(index) else { return .discarded }
                evidence = units[index]; return .handled
            })
    }
    private func attributed(_ claim: SummaryClaim) -> AttributedString {
        var text = AttributedString(claim.text)
        let labels = ["¹", "²", "³"]
        for index in claim.citations.indices {
            var reference = AttributedString(" " + labels[index])
            reference.link = URL(string: "campus-evidence://\(index)")
            reference.foregroundColor = .blue
            text += reference
        }
        return text
    }
}
