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
        .background(Palette.canvas)
        .onChange(of: model.phase) { _, phase in
            if case .connected = phase { onConnected() }
        }
    }

    private var header: some View {
        VStack(spacing: 10) {
            NanobotMark(height: 64)
                .accessibilityIdentifier("onboarding.mark")
            Text("nanobot Companion")
                .font(.system(size: 11, weight: .bold))
                .tracking(1.0)
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
            Text("Connect nanobot")
                .font(.system(size: 24, weight: .semibold))
                .tracking(-0.5)
            Text(ConnectCopy.body)
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
            WebUIIcon(name: "Warning", size: 13)
                .padding(.top, 1)
            Text(text)
                .font(.system(size: 13))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Palette.destructive)
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.destructive.opacity(0.10), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(Palette.destructive.opacity(0.26), lineWidth: 1)
        )
        .padding(.bottom, 14)
        .accessibilityIdentifier("connect.error")
    }

    private var addressField: some View {
        field(label: "Gateway address", icon: "Server") {
            TextField("", text: $model.address)
                // A URL-shaped prompt gets linkified by the system, which painted the hint
                // blue. The placeholder is a hint, so it is drawn as one.
                .overlay(alignment: .leading) {
                    if model.address.isEmpty {
                        Text("http://192.168.1.10:8787")
                            .foregroundStyle(.secondary)
                            .allowsHitTesting(false)
                    }
                }
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
        field(label: "API key", icon: "Key") {
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
                WebUIIcon(name: icon, size: 14)
                    .foregroundStyle(Palette.mutedForeground)
                content()
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 46)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Palette.secondarySurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(Palette.border, lineWidth: 1)
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
        .tint(Palette.primary)
        .frame(minHeight: 44)
        .accessibilityIdentifier("connect.allowUnverified")
        .padding(.bottom, 16)
        .overlay(alignment: .bottom) {
            if model.trustSwitchIsTheRemedy {
                Text("Turn this on if this gateway is yours, then connect again.")
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.destructive)
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
                    ProgressView().controlSize(.small).tint(Palette.onPrimary)
                }
                Text(model.isConnecting ? "Connecting" : "Connect")
                    .font(.system(size: 16, weight: .semibold))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(Palette.primary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .foregroundStyle(Palette.onPrimary)
        }
        .disabled(!model.canConnect)
        .opacity(model.canConnect ? 1 : 0.45)
        .accessibilityIdentifier("connect.submit")
        .padding(.top, model.trustSwitchIsTheRemedy ? 24 : 0)
    }

    private var footer: some View {
        VStack(spacing: 6) {
            Text("The key stays in the keychain and renews the session token, which expires in minutes.")
            Text(ConnectCopy.disclaimer)
                .accessibilityIdentifier("onboarding.disclaimer")
        }
        .font(.system(size: 11))
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .padding(.top, 20)
    }
}
