import Foundation

/// The composer's attachments (plan.md I6): pick, upload, show as a card, remove. Every limit
/// and every refusal is the gateway's — this asks the core and draws the answer, so a rejection
/// cannot drift from the one the gateway would give.
///
/// The card goes up with the pick, before a byte leaves the phone. Uploading first and drawing
/// afterwards left the composer empty for as long as the link took, which is what the owner saw
/// as "the attachment does nothing"; and a pick the gateway refused produced a sentence drawn
/// inside a strip that is hidden when there are no cards, so a first pick could fail in silence.
@MainActor
final class AttachmentViewModel: ObservableObject {

    @Published private(set) var attachments: [PendingAttachment] = []
    /// Why a pick never became a card, with the file's name in it.
    @Published private(set) var notice: UploadNotice?
    /// The index of the image the lightbox is showing, if it is open.
    @Published private(set) var lightbox: Int?
    @Published private(set) var sheetOpen = false

    let limits: UploadLimits

    private var driver: AttachmentDriving
    /// The bytes of a card that has not landed yet, so a failure can be tried again without
    /// asking the reader to find the photo a second time. Cleared as soon as a card is ready —
    /// at most four files, and the gateway's own 6 MiB each.
    private var picks: [String: PickedAttachment] = [:]
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

    /// The reason, without the file: the sentence is the core's copy, so it can be asserted
    /// against the refusal table while the name sits beside it (`notice.name`).
    var lastRejection: String? { notice?.sentence }

    /// Whether the composer draws the strip: cards, or a refusal that has no card to sit on.
    var showsStrip: Bool { !attachments.isEmpty || notice != nil }

    /// An upload that has not landed yet is not on the message, so the composer may not send.
    var isUploading: Bool { attachments.contains { $0.state == .uploading } }

    /// The screen swaps in the chat once a gateway is open, or a scripted uploader in a flow test.
    /// Switching chats swaps the cards with it, because each chat keeps its own.
    func use(_ driver: AttachmentDriving, chatId: String?) {
        self.driver = driver
        chatKey = chatId ?? ""
        attachments = cardsByChat[chatKey] ?? []
        picks = picks.filter { id, _ in attachments.contains { $0.id == id } }
        lightbox = nil
        notice = nil
    }

    func openSheet() { sheetOpen = true }
    func closeSheet() { sheetOpen = false }

    /// One picked file: a card at once, then the core validates against `ready.limits` and
    /// uploads. A refusal is a sentence naming the file; a link failure leaves a card to retry.
    func add(_ picked: PickedAttachment) async {
        notice = nil
        nextId += 1
        let id = "attach-\(nextId)"
        attachments.append(
            PendingAttachment(
                id: id,
                name: picked.name,
                mimeType: picked.mimeType,
                size: picked.size,
                state: .uploading,
                url: picked.url,
                preview: picked.preview
            )
        )
        cardsByChat[chatKey] = attachments
        await upload(id: id, picked: picked)
    }

    /// The bytes go up once, from whichever door the pick came through. The preparation is
    /// CPU work — a 48-megapixel HEIC takes hundreds of milliseconds — so it is not done on the
    /// main actor; only the card's own updates are.
    private func upload(id: String, picked: PickedAttachment) async {
        let takesIt = driver.accepts(mimeType: picked.mimeType)
        let maxBytes = limits.maxFileBytes
        let prepared = await Task.detached(priority: .userInitiated) {
            ImagePreparation().prepare(picked, maxBytes: maxBytes, mustConvert: !takesIt)
        }.value
        guard attachments.contains(where: { $0.id == id }) else { return }

        picks[id] = prepared
        update(id) { card in
            card.name = prepared.name
            card.mimeType = prepared.mimeType
            card.size = prepared.size
            card.preview = prepared.preview ?? card.preview
        }

        switch await driver.upload(name: prepared.name, mimeType: prepared.mimeType, data: prepared.data) {
        case let .uploaded(reference):
            picks[id] = nil
            update(id) { $0.state = .ready(reference: reference) }
        case let .rejected(reason):
            // The gateway would refuse the same bytes again, so this is a sentence with the
            // file's name in it rather than a card the reader can never send.
            picks[id] = nil
            drop(id)
            notice = UploadNotice(name: prepared.name, sentence: reason.copy(limits: limits))
        case .failed:
            // The link failed, not the file: the card keeps its bytes and its reason.
            update(id) { $0.state = .failed(reason: "The upload did not reach the gateway.") }
        }
    }

    /// Try one attachment again with the bytes it already has.
    func retry(id: String) async {
        guard let picked = picks[id], attachments.contains(where: { $0.id == id }) else { return }
        notice = nil
        update(id) { $0.state = .uploading }
        await upload(id: id, picked: picked)
    }

    /// A pick the phone refused before there were any bytes — the camera switched off in
    /// Settings. The same row as an upload refusal, because it is the same thing to the reader.
    func note(_ notice: UploadNotice) {
        self.notice = notice
    }

    func dismissNotice() {
        notice = nil
    }

    func remove(id: String) {
        picks[id] = nil
        drop(id)
        lightbox = nil
    }

    /// A message this chat's gateway took carries its uploads, so only its cards go.
    func clear() {
        attachments = []
        picks = [:]
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

    /// What the send carries: the references the gateway handed back (C6.6). A card still
    /// uploading has none, which is why the composer refuses to send while one is in flight.
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

    private func update(_ id: String, _ change: (inout PendingAttachment) -> Void) {
        guard let index = attachments.firstIndex(where: { $0.id == id }) else { return }
        change(&attachments[index])
        cardsByChat[chatKey] = attachments
    }

    private func drop(_ id: String) {
        attachments.removeAll { $0.id == id }
        cardsByChat[chatKey] = attachments
    }
}
