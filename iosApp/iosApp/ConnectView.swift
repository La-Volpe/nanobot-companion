import SwiftUI

/// Onboarding (plan.md I1). Every control reads one state and calls one command; the branch
/// logic lives in `ConnectViewModel`, and the layout follows the design artifact's
/// `data-view="onboarding"`: kicker, title, a reading-scale paragraph, two fields, the
/// unverified-certificate switch, one filled primary button.
struct ConnectView: View {
    @ObservedObject var model: ConnectViewModel
    let onConnected: () -> Void

    @FocusState private var focused: Field?

    private enum Field { case address, key }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                header
                if let message = model.message, model.phase != .connecting {
                    errorSurface(message)
                }
                addressField
                keyField
                trustToggle
                connectButton
                footer
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
        }
        .background(Color(.systemBackground))
        .onChange(of: model.phase) { _, phase in
            if case .connected = phase { onConnected() }
        }
    }

    private var header: some View {
        VStack(spacing: 10) {
            Text("nanobot Companion")
                .font(.system(size: 11, weight: .bold))
                .tracking(1.0)
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
            Text("Connect nanobot")
                .font(.system(size: 24, weight: .semibold))
                .tracking(-0.5)
            Text("Point the app at the gateway on your Mac or server. Nothing leaves your network.")
                .font(ReadingType.body)
                .lineSpacing(ReadingType.lineSpacing)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 290)
        }
        .padding(.top, 26)
        .padding(.bottom, 22)
    }

    /// The design's destructive pair at 10% fill with a 26% outline: one sentence, no stack of
    /// them, so the fix stays visible above the field it belongs to.
    private func errorSurface(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 13))
                .padding(.top, 1)
            Text(text)
                .font(.system(size: 13))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Color.red)
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Color.red.opacity(0.26), lineWidth: 1)
        )
        .padding(.bottom, 14)
        .accessibilityIdentifier("connect.error")
    }

    private var addressField: some View {
        field(label: "Gateway address", icon: "server.rack") {
            TextField(
                "",
                text: $model.address,
                prompt: Text("http://192.168.1.10:8787").foregroundColor(.secondary)
            )
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused, equals: .address)
                .accessibilityIdentifier("connect.address")
                .onChange(of: model.address) { _, _ in model.clearMessage() }
        }
    }

    private var keyField: some View {
        field(label: "API key", icon: "key") {
            SecureField(
                "",
                text: $model.key,
                prompt: Text("WebUI secret from the gateway").foregroundColor(.secondary)
            )
                .textContentType(.password)
                .focused($focused, equals: .key)
                .accessibilityIdentifier("connect.key")
                .onChange(of: model.key) { _, _ in model.clearMessage() }
            Text("required")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        }
    }

    private func field<Content: View>(
        label: String,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                content()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 46)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(.secondarySystemBackground))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1)
            )
        }
        .padding(.bottom, 14)
    }

    /// The switch that makes a self-signed home gateway usable. Off by default, and turning it
    /// on does not retry: the user decides, then presses Connect (C3.2, D21).
    private var trustToggle: some View {
        Toggle(isOn: Binding(
            get: { model.allowUnverifiedCertificate },
            set: { model.setAllowUnverifiedCertificate($0) }
        )) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Allow unverified certificate")
                    .font(.system(size: 15))
                Text("Self-signed HTTPS on a home server")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
        .tint(.primary)
        .frame(minHeight: 44)
        .accessibilityIdentifier("connect.allowUnverified")
        .padding(.bottom, 16)
        .overlay(alignment: .bottom) {
            if model.trustSwitchIsTheRemedy {
                Text("Turn this on if this gateway is yours, then connect again.")
                    .font(.system(size: 13))
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .offset(y: 12)
            }
        }
    }

    /// Monochrome, as the design system requires: primary actions are never brand orange.
    private var connectButton: some View {
        Button {
            Task { await model.connect() }
        } label: {
            HStack(spacing: 8) {
                if model.isConnecting {
                    ProgressView().controlSize(.small).tint(Color(.systemBackground))
                }
                Text(model.isConnecting ? "Connecting" : "Connect")
                    .font(.system(size: 16, weight: .semibold))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Color.primary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .foregroundStyle(Color(.systemBackground))
        }
        .disabled(!model.canConnect)
        .opacity(model.canConnect ? 1 : 0.45)
        .accessibilityIdentifier("connect.submit")
        .padding(.top, model.trustSwitchIsTheRemedy ? 24 : 0)
    }

    private var footer: some View {
        Text("The key stays in the keychain and renews the session token, which expires in minutes.")
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.top, 20)
    }
}
