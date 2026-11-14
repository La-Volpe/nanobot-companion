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
    func use(_ driver: AttachmentDriving, clear: Bool) {
        self.driver = driver
        if clear { self.attachments = []; lightbox = nil; lastRejection = nil }
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
        case let .rejected(reason):
            lastRejection = reason.copy(limits: limits)
        case .failed:
            lastRejection = "The upload did not reach the gateway."
        }
    }

    func remove(id: String) {
        attachments.removeAll { $0.id == id }
        lightbox = nil
    }

    func clear() {
        attachments = []
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
}
