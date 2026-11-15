import Foundation
import Testing
@testable import iosApp

/// The one primary button has three jobs and is pressed by one finger (T31, the owner's report:
/// "the voice option is activated with send button and begins to record"). The send empties the
/// field, so the button becomes the microphone *while that finger is still down*, and the gesture
/// that belonged to the send must not be allowed to start anything. This is the guard, as a
/// value, so the rule is a test instead of a gesture callback.
struct PrimaryPressTests {

    private typealias State = ComposerViewModel.PrimaryState

    /// The press began on the microphone: it may record.
    @Test func aPressThatBeganOnTheMicrophoneRecords() {
        var press = PrimaryPress()

        let atDown = press.down(.voice)
        let later = press.down(.voice)

        #expect(atDown, "the microphone was the button when the finger went down")
        #expect(later, "and every later event of that press agrees")
    }

    /// The owner's bug: the finger went down on Send, the send emptied the field, the button
    /// became the microphone, and the next event of the *same* press armed the recorder.
    @Test func aPressThatBeganOnSendNeverArmsTheRecorder() {
        var press = PrimaryPress()

        let atDown = press.down(.send)

        // The action ran and cleared the field: the button is a microphone now.
        press.acted()
        let afterTheSend = press.down(.voice)

        #expect(atDown == false, "this press is a message, not a recording")
        #expect(afterTheSend == false, "the state changed under the finger; the press did not")
    }

    /// The other ordering SwiftUI is free to choose: the button's action lands after the gesture
    /// has already ended. It belongs to the press that is over, so the next press still records.
    @Test func anActionDeliveredAfterTheFingerIsUpLeavesTheNextPressAlone() {
        var press = PrimaryPress()
        _ = press.down(.send)
        press.lifted()
        press.acted()

        let next = press.down(.voice)

        #expect(next, "a fresh press on the microphone is a recording")
    }

    /// A press that stops a turn is the Stop button, not the microphone.
    @Test func aPressThatBeganOnStopDoesNotRecord() {
        var press = PrimaryPress()

        let atDown = press.down(.stop)
        press.acted()
        let afterTheStop = press.down(.voice)

        #expect(atDown == false)
        #expect(afterTheStop == false)
    }

    /// The next press is a new question: after the field is empty and the finger is up, the
    /// microphone records again.
    @Test func thePressAfterASendMayRecordAgain() {
        var press = PrimaryPress()
        _ = press.down(.send)
        press.acted()
        press.lifted()

        let fresh = press.down(.voice)

        #expect(fresh, "a fresh press on the microphone is a recording")
    }
}
