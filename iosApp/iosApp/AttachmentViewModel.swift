import Foundation

/// The composer's attachments (plan.md I6): pick, upload, show as a card, remove. Every limit
/// and every refusal is the gateway's — this asks the core and draws the answer, so a rejection
/// cannot drift from the one the gateway would give.
@MainActor
final class AttachmentViewModel: ObservableObject {

    @Published private(set) var attachments: [PendingAttachment] = []
    @Published private(set) var lastRejection: String?
    /// The index of the image the lightbox is showing, if it is open.
    @Published private(set) var lightbox: Int?
    @Published private(set) var sheetOpen = false

    let limits: UploadLimits

    private var driver: AttachmentDriving
    /// The chat the cards on screen belong to. Nil is the chat being composed before the gateway
    /// names it, which is why it is a key rather than an optional id.
    private var chatKey = ""
    /// Every chat's cards, kept apart: a card names an upload reference that belongs to one chat's
    /// session, so showing it in another one promises a message that chat cannot carry (P13).
    private var cardsByChat: [String: [PendingAttachment]] = [:]
    /// Stable, so a card's identity survives the attachment list changing around it.
    private var nextId = 0

    init(driver: AttachmentDriving, limits: UploadLimits = .fallback) {
        self.driver = driver
        self.limits = limits
    }

    /// The cards by kind — the grid, the video card and the file cards the design draws.
    var images: [PendingAttachment] { attachments.filter { $0.kind == .image } }
    var videos: [PendingAttachment] { attachments.filter { $0.kind == .video } }
    var files: [PendingAttachment] { attachments.filter { $0.kind == .file || $0.kind == .audio } }

    var lightboxImage: PendingAttachment? {
        guard let lightbox, images.indices.contains(lightbox) else { return nil }
        return images[lightbox]
    }

    /// The screen swaps in the chat once a gateway is open, or a scripted uploader in a flow test.
    /// Switching chats swaps the cards with it, because each chat keeps its own.
    func use(_ driver: AttachmentDriving, chatId: String?) {
        self.driver = driver
        chatKey = chatId ?? ""
        attachments = cardsByChat[chatKey] ?? []
        lightbox = nil
        lastRejection = nil
    }

    func openSheet() { sheetOpen = true }
    func closeSheet() { sheetOpen = false }

    /// One picked file: the core validates against `ready.limits` and uploads; a refusal is a
    /// sentence, and a refused file never becomes a card.
    func add(_ picked: PickedAttachment) async {
        lastRejection = nil
        let outcome = await driver.upload(name: picked.name, mimeType: picked.mimeType, data: picked.data)
        switch outcome {
        case let .uploaded(reference):
            nextId += 1
            attachments.append(
                PendingAttachment(
                    id: "attach-\(nextId)",
                    name: picked.name,
                    mimeType: picked.mimeType,
                    size: picked.size,
                    kind: AttachmentKind(mimeType: picked.mimeType),
                    reference: reference,
                    url: picked.url
                )
            )
            cardsByChat[chatKey] = attachments
        case let .rejected(reason):
            lastRejection = reason.copy(limits: limits)
        case .failed:
            lastRejection = "The upload did not reach the gateway."
        }
    }

    func remove(id: String) {
        attachments.removeAll { $0.id == id }
        cardsByChat[chatKey] = attachments
        lightbox = nil
    }

    /// A message this chat's gateway took carries its uploads, so only its cards go.
    func clear() {
        attachments = []
        cardsByChat[chatKey] = []
        lightbox = nil
    }

    func openLightbox(at index: Int) {
        guard images.indices.contains(index) else { return }
        lightbox = index
    }

    func closeLightbox() {
        lightbox = nil
    }

    /// What the send carries: the references the gateway handed back (C6.6).
    var references: [String] {
        attachments.compactMap(\.reference)
    }

    /// The same cards as a message names them — reference and file name — so the send can say
    /// what it is carrying instead of leaving it to a side effect (P13).
    var uploads: [SentAttachment] {
        attachments.compactMap { card in
            card.reference.map { SentAttachment(reference: $0, name: card.name) }
        }
    }
}
