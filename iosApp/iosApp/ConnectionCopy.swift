import Foundation

/// The eight sentences a failed connect can show, plus the three local refusals that never leave
/// the phone. The keys are the core's copy keys, so a Swift string and a Kotlin taxonomy entry
/// cannot drift apart silently: `CopyTests` asserts the pairing.
///
/// This file deliberately imports nothing but Foundation: it is the copy, not the framework.
enum ConnectionCopy {
    static func text(for failure: ConnectionFailureKind, detail: String?) -> String {
        switch failure {
        case .authFailed:
            "That key was refused. Check the gateway's key and try again."
        case .tlsRejected:
            "This gateway's certificate is not trusted. If it is your own, allow it in this gateway's settings."
        case .unreachable:
            "No gateway at that address. Check the address and that the gateway is running."
        case .timeout:
            "The gateway did not answer in time. Try again."
        case .protocolTooNew:
            "This gateway speaks a newer protocol than this app. Update the app."
        case .missingCapability:
            "This gateway does not offer that feature. The rest of the chat still works."
        case .missingField:
            "The gateway's answer was incomplete. Check its version."
        case .serverError:
            // 5xx may be a bad minute; a 4xx is the same answer every time.
            switch detail.flatMap(Int.init) {
            case let status? where status >= 500: "The gateway refused the request. Try again in a moment."
            default: "The gateway refused the request."
            }
        }
    }

    /// A failure the phone catches itself, before any request is made (design: onboarding's
    /// "invalid URL" and "missing key" states).
    enum LocalRefusal: Equatable {
        case addressNeedsScheme
        case keyMissing
        case addressMissing

        var text: String {
            switch self {
            case .addressNeedsScheme: "Enter a full address, including http:// or https://."
            case .keyMissing: "The gateway rejected the request: no API key. /webui/bootstrap needs the WebUI secret."
            case .addressMissing: "Enter the address of your gateway."
            }
        }
    }
}

/// The taxonomy as Swift can name it, without importing the framework into this file: the
/// adapter layer maps `core.ConnectionFailure` onto this, and every view model talks to it.
enum ConnectionFailureKind: String, CaseIterable {
    case authFailed
    case tlsRejected
    case unreachable
    case timeout
    case protocolTooNew
    case missingCapability
    case missingField
    case serverError

    /// Whether "try again" is an honest offer, straight from the core's own decision.
    var isRetryable: Bool {
        switch self {
        case .unreachable, .timeout, .serverError: true
        case .authFailed, .tlsRejected, .protocolTooNew, .missingCapability, .missingField: false
        }
    }

    /// The copy key the core names, kept here so the pairing is asserted rather than assumed.
    var copyKey: String {
        switch self {
        case .authFailed: "connection.authFailed"
        case .tlsRejected: "connection.tlsRejected"
        case .unreachable: "connection.unreachable"
        case .timeout: "connection.timeout"
        case .protocolTooNew: "connection.protocolTooNew"
        case .missingCapability: "connection.missingCapability"
        case .missingField: "connection.missingField"
        case .serverError: "connection.serverError"
        }
    }
}
