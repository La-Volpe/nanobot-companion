import AVKit
import SwiftUI
import UIKit

/// One media row on a turn: a picture, a clip, a recording or a file card.
///
/// The three shapes are the WebUI's own (`webui/src/components/AttachmentTile.tsx:33`): an image
/// is inline with a lightbox, a video is a framed player, and everything else is a file card. What
/// the WebUI does not have is an audio row, because it has no player for one, so that is ours.
struct MediaRowView: View {
    let row: MediaRow

    var body: some View {
        switch row.kind {
        case .image:
            MediaImageView(row: row)
        case .video:
            MediaVideoRow(row: row)
        case .audio:
            MediaAudioRow(row: row)
        case .file:
            MediaFileCard(row: row)
        }
    }
}

/// An inline picture. Loading is a skeleton at its own height, a failure is a card with the
/// sentence and a retry, never an empty frame the reader has to guess about.
struct MediaImageView: View {
    let row: MediaRow
    @StateObject private var loader = MediaLoader()

    var body: some View {
        Group {
            switch loader.phase {
            case .idle, .loading:
                RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                    .fill(Palette.secondarySurface)
                    .frame(height: 180)
                    .overlay(ProgressView().controlSize(.small))
                    .accessibilityIdentifier("media.loading")
            case .ready:
                if let image = loader.image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .clipShape(RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous))
                        .accessibilityIdentifier("media.image")
                        .accessibilityLabel(row.name.isEmpty ? "Image" : row.name)
                } else {
                    failure
                }
            case .failed:
                failure
            }
        }
        .task(id: row.url) {
            if let url = row.url { await loader.load(url) }
        }
    }

    private var failure: some View {
        MediaFailureCard(title: "Could not load this image.", identifier: "media.imageFailed") {
            Task { await loader.retry() }
        }
    }
}

/// A failure the reader can act on: the sentence, and the retry that asks again.
struct MediaFailureCard: View {
    let title: String
    let identifier: String
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Text(title)
                .font(Design.Interface.caption)
                .foregroundStyle(Palette.mutedForeground)
                .accessibilityIdentifier(identifier)
            Spacer(minLength: 0)
            Button("Retry", action: onRetry)
                .font(Design.Interface.label)
                .frame(minHeight: Design.minimumTarget)
                .accessibilityIdentifier("\(identifier).retry")
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.secondarySurface)
        )
    }
}

/// The face of something that is not a picture: the kind, the name, and, when the gateway could
/// not sign a url, the word that says it cannot be opened.
struct MediaFileCard: View {
    let row: MediaRow

    var body: some View {
        HStack(spacing: 10) {
            Text(row.kind.face)
                .font(Design.Interface.label)
                .foregroundStyle(Palette.mutedForeground)
                .frame(width: 36, height: 36)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Palette.canvas)
                )
            VStack(alignment: .leading, spacing: 1) {
                Text(row.name.isEmpty ? "Attachment" : row.name)
                    .font(Design.Interface.caption)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !row.loadable {
                    Text("Unavailable")
                        .font(Design.Interface.micro)
                        .foregroundStyle(Palette.mutedForeground)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: Design.minimumTarget)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.secondarySurface)
        )
        .accessibilityIdentifier("media.file")
    }
}

/// A clip: a framed row, and tapping it opens the system player in a sheet rather than a
/// hand-rolled transport (`AVPlayerViewController` draws the controls iOS users expect).
struct MediaVideoRow: View {
    let row: MediaRow
    @State private var playing = false

    var body: some View {
        Button {
            playing = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "play.circle")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(Palette.foreground)
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.name.isEmpty ? "Video" : row.name)
                        .font(Design.Interface.caption)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(row.loadable ? "Tap to play" : "Unavailable")
                        .font(Design.Interface.micro)
                        .foregroundStyle(Palette.mutedForeground)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(minHeight: Design.minimumTarget)
            .background(
                RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                    .fill(Palette.secondarySurface)
            )
        }
        .buttonStyle(.plain)
        .disabled(!row.loadable)
        .accessibilityIdentifier("media.video")
        .sheet(isPresented: $playing) {
            if let url = row.url {
                VideoPlayerScreen(url: url)
            }
        }
    }
}

/// `AVPlayerViewController` in a representable: the small way to get scrubbing, full screen,
/// AirPlay and Picture in Picture without owning any of that code.
struct VideoPlayerScreen: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        let player = AVPlayer(url: url)
        controller.player = player
        controller.modalPresentationStyle = .pageSheet
        player.play()
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: ()) {
        // A player nobody dismissed keeps playing with the sheet gone, which is the audio-bug
        // shape on iOS: stop it with the view that owns it.
        controller.player?.pause()
        controller.player = nil
    }
}

/// A recording, played in place: one row with a play/pause control and the line that says how
/// far in it is. The simulator has no audio device, so what is proved here is the state and the
/// line; whether it is audible needs a phone.
struct MediaAudioRow: View {
    let row: MediaRow
    @StateObject private var player: AudioPlayerModel
    @State private var ticker: Timer?

    init(row: MediaRow) {
        self.row = row
        let url = row.url ?? URL(string: "about:blank")!
        _player = StateObject(wrappedValue: AudioPlayerModel(url: url))
    }

    var body: some View {
        HStack(spacing: 10) {
            Button {
                Task { await player.toggle() }
            } label: {
                Image(systemName: player.phase == .playing ? "pause.circle" : "play.circle")
                    .font(.system(size: 26, weight: .regular))
                    .foregroundStyle(Palette.foreground)
                    .frame(width: Design.minimumTarget, height: Design.minimumTarget)
            }
            .buttonStyle(.plain)
            .disabled(!row.loadable)
            .accessibilityIdentifier("media.audio.toggle")

            VStack(alignment: .leading, spacing: 4) {
                Text(row.name.isEmpty ? "Audio" : row.name)
                    .font(Design.Interface.caption)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if player.phase == .failed {
                    HStack(spacing: 8) {
                        Text("Could not play this recording.")
                            .font(Design.Interface.micro)
                            .foregroundStyle(Palette.mutedForeground)
                            .accessibilityIdentifier("media.audioFailed")
                        Button("Retry") { Task { await player.retry() } }
                            .font(Design.Interface.label)
                            .accessibilityIdentifier("media.audioFailed.retry")
                    }
                } else {
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Palette.border)
                            Capsule()
                                .fill(Palette.foreground)
                                .frame(width: geometry.size.width * player.progress)
                        }
                    }
                    .frame(height: 3)
                    .accessibilityIdentifier("media.audio.progress")
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: Design.minimumTarget)
        .background(
            RoundedRectangle(cornerRadius: Design.Radius.field, style: .continuous)
                .fill(Palette.secondarySurface)
        )
        .onChange(of: player.phase) { _, phase in
            setTicker(running: phase == .playing)
        }
        .onDisappear { setTicker(running: false) }
    }

    private func setTicker(running: Bool) {
        ticker?.invalidate()
        ticker = nil
        guard running else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
            Task { @MainActor in player.tick() }
        }
    }
}
