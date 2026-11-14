#if DEBUG
import SwiftUI

/// The debug menu I8 and G2 ask for: every one of the eight connection failures, plus the chat
/// states the design's chips inject, reachable by hand rather than only by launch environment.
///
/// It is DEBUG-only, so a release build has no way to reach it. It injects the same state the
/// real path produces — the banner, the dot and the notice rows are the app's own views — so
/// what a person sees here is what a real failure looks like.
struct DebugMenu: View {
    @ObservedObject var states: StatesViewModel
    @ObservedObject var composer: ComposerViewModel

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Connection: the eight failures") {
                    ForEach(DebugState.failureScenarios) { scenario in
                        Button {
                            states.debugFail(scenario.kind, detail: scenario.detail)
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(scenario.title)
                                    .font(.system(size: Design.Interface.bodySize))
                                Text(ConnectionCopy.text(for: scenario.kind, detail: scenario.detail))
                                    .font(.system(size: Design.Interface.microSize))
                                    .foregroundStyle(.secondary)
                            }
                            .frame(minHeight: Design.minimumTarget, alignment: .leading)
                        }
                        .accessibilityIdentifier("debug.failure.\(scenario.kind.rawValue)")
                    }
                }
                Section("Chat states") {
                    ForEach(DebugState.ChatState.allCases) { state in
                        Button(state.title) {
                            states.debugInject(state, composer: composer)
                            dismiss()
                        }
                        .frame(minHeight: Design.minimumTarget)
                        .accessibilityIdentifier("debug.state.\(state.rawValue)")
                    }
                }
                Section {
                    Button("Clear every injected state") {
                        states.debugClear()
                        dismiss()
                    }
                    .frame(minHeight: Design.minimumTarget)
                    .accessibilityIdentifier("debug.clear")
                }
            }
            .navigationTitle("Debug states")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }
                        .accessibilityIdentifier("debug.close")
                }
            }
        }
    }
}

#endif
