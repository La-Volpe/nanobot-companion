import Foundation

/// The onboarding sentences that make a claim about the world, so both platforms have to word
/// them the same. The rest of the onboarding copy is layout and stays in the view body; these are
/// read by `tools/parity_copy.py` against `res/values/strings.xml`. `body` says where a message
/// goes, and the provenance line says who this app is not.
///
/// Like `ConnectionCopy`, this file imports nothing but Foundation: it is copy, not a view.
enum ConnectCopy {
    static let body = "Point the app at the gateway on your Mac or server. There is no companion server: your messages and the files you attach go to that gateway, and what it does with them from there is its own configuration."

    /// The name and the day/night mark are nanobot's, kept on purpose. MIT grants no
    /// trademark right and both stores forbid another product's identity without approval, so the
    /// app says plainly what it is not, in the place a reader looks for provenance.
    static let disclaimer = "Not affiliated with HKUDS/nanobot."
}
