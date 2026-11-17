import Foundation

/// The stored gateway key, as the app sees it. A seam rather than a call so the
/// launch path and the connect screen can be tested with a fake, and so the Keychain has
/// exactly one implementation, in the adapter layer, over `:core`'s own store.
protocol SecretStoring: AnyObject {
    func read() -> String?
    func store(_ secret: String)
    func forget()
}

/// Where the address and the unverified-certificate answer live: the app's own defaults, not
/// the Keychain. Neither is a credential; the address is a setting the user can read back.
protocol CredentialsStoring: AnyObject {
    var address: String? { get }
    /// The key this device already holds, so a launch can reconnect instead of asking again.
    var storedKey: String? { get }
    var allowUnverifiedCertificate: Bool { get }
    func remember(address: String, key: String, allowUnverifiedCertificate: Bool)
    func setAllowUnverifiedCertificate(_ allowed: Bool)
    func forget()
}

/// The production store: address and switch in `UserDefaults`, the key in the Keychain.
final class StoredCredentials: CredentialsStoring {
    private enum Key {
        static let address = "gateway.address"
        static let allowUnverified = "gateway.allowUnverifiedCertificate"
    }

    private let defaults: UserDefaults
    private let secrets: SecretStoring

    init(defaults: UserDefaults = .standard, secrets: SecretStoring) {
        self.defaults = defaults
        self.secrets = secrets
    }

    var address: String? { defaults.string(forKey: Key.address) }

    var storedKey: String? { secrets.read() }

    var allowUnverifiedCertificate: Bool { defaults.bool(forKey: Key.allowUnverified) }

    /// The key is written only after the gateway accepted it, so a typo is never made durable.
    func remember(address: String, key: String, allowUnverifiedCertificate: Bool) {
        defaults.set(address, forKey: Key.address)
        defaults.set(allowUnverifiedCertificate, forKey: Key.allowUnverified)
        secrets.store(key)
    }

    func setAllowUnverifiedCertificate(_ allowed: Bool) {
        defaults.set(allowed, forKey: Key.allowUnverified)
    }

    /// Settings → Gateway: the address goes with the key, so the app returns to onboarding.
    func forget() {
        defaults.removeObject(forKey: Key.address)
        defaults.set(false, forKey: Key.allowUnverified)
        secrets.forget()
    }
}
