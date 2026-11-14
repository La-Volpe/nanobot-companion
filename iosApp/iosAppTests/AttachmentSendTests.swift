import Foundation
import Testing
@testable import iosApp

/// P13: a picked attachment must ride the message the composer sends.
///
/// The cards and the send met only in `ComposerViews.primaryAction()`, which cleared them when
/// the send was accepted — so nothing but a tap could show whether the references the gateway
/// handed back were in the draft at all, and the composer's own draft built them empty. The
/// rule lives in the model now: what the composer shows is what it sends, and cards only leave
/// once the gateway took the message (the Android half's rule, D49).
@MainActor
struct AttachmentSendTests {

    /// The composer's driver, remembering what each send carried.
    final class SendingDriver: ComposerDriving {
        var sends: [(text: String, media: [SentAttachment])] = []
        var accepted = true

        func sendPrompt(_ text: String, media: [SentAttachment]) async -> Bool {
            sends.append((text, media))
            return accepted
        }

        func enqueue(_ text: String) {}
        func stop() async {}
        func sendQueuedNow() async {}
        func discardQueued() {}
        func setModelPreset(_ preset: String) async {}
        func setWorkspaceScope(_ access: WorkspaceAccess) async {}
        func loadPalette(text: String, cursor: Int) async -> PaletteSheet? { nil }
    }

    final class FakeUploader: AttachmentDriving {
        var outcome: AttachmentOutcome = .uploaded(reference: "ref-1")

        func upload(name: String, mimeType: String, data: Data) async -> AttachmentOutcome { outcome }
    }

    private func composer(_ driver: SendingDriver, _ uploader: FakeUploader) -> (ComposerViewModel, AttachmentViewModel) {
        let attachments = AttachmentViewModel(driver: uploader)
        let model = ComposerViewModel(driver: driver)
        model.attach(attachments: attachments)
        return (model, attachments)
    }

    private func image(_ name: String = "IMG_1.png") -> PickedAttachment {
        PickedAttachment(name: name, mimeType: "image/png", data: Data(repeating: 0, count: 1_000), url: nil)
    }

    @Test func aPickedAttachmentRidesTheMessageThatLeaves() async {
        let driver = SendingDriver()
        let uploader = FakeUploader()
        let (model, attachments) = composer(driver, uploader)

        await attachments.add(image())
        model.draft = "what is in this picture?"
        await model.primaryAction()

        let sent = driver.sends.first
        #expect(sent?.text == "what is in this picture?")
        #expect(sent?.media.map(\.reference) == ["ref-1"], "the reference the gateway handed back is in the draft, not only on a card")
        #expect(attachments.attachments.isEmpty, "a message the gateway took carries the uploads, so the cards go with it")
    }

    @Test func twoUploadsBothRideTheOneMessage() async {
        let driver = SendingDriver()
        let uploader = FakeUploader()
        let (model, attachments) = composer(driver, uploader)

        uploader.outcome = .uploaded(reference: "ref-1")
        await attachments.add(image("one.png"))
        uploader.outcome = .uploaded(reference: "ref-2")
        await attachments.add(image("two.png"))
        model.draft = "compare these"
        await model.primaryAction()

        #expect(driver.sends.first?.media.map(\.reference) == ["ref-1", "ref-2"], "every card on screen is on the message")
        #expect(driver.sends.first?.media.map(\.name) == ["one.png", "two.png"], "and the name the gateway stores with it")
    }

    @Test func aRefusedMessageKeepsItsCards() async {
        let driver = SendingDriver()
        driver.accepted = false
        let uploader = FakeUploader()
        let (model, attachments) = composer(driver, uploader)

        await attachments.add(image())
        model.draft = "does this go?"
        await model.primaryAction()

        #expect(driver.sends.count == 1)
        #expect(model.draft == "does this go?", "a refused send gives the text back (C6.1)")
        #expect(attachments.references == ["ref-1"], "and the upload is still there to send with the next try")
    }

    @Test func anEmptyComposerCarriesNothing() async {
        let driver = SendingDriver()
        let uploader = FakeUploader()
        let (model, _) = composer(driver, uploader)

        model.draft = "no attachments here"
        await model.primaryAction()

        #expect(driver.sends.first?.media.isEmpty == true)
    }

    /// While a turn runs, typing queues instead of sending — and the cards stay for the message
    /// that comes later, unlike a send that empties them.
    @Test func queueingDoesNotSpendTheCards() async {
        let driver = SendingDriver()
        let uploader = FakeUploader()
        let (model, attachments) = composer(driver, uploader)

        await attachments.add(image())
        model.update(streaming: true)
        model.draft = "one more thing"
        await model.primaryAction()

        #expect(driver.sends.isEmpty, "a queued prompt is a steer, not a send")
        #expect(attachments.references == ["ref-1"])
    }
}
