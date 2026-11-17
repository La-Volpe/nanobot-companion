import Foundation
import Testing

/// The two rules a hand-written Xcode project otherwise has to be trusted for:
/// only the adapter layer touches the Kotlin framework, and every source file is actually in
/// the project: a file missing from `project.pbxproj` does not fail a build, it just silently
/// never compiles, which is the worst way to find out a test does not exist.
struct LayeringTests {

    /// The adapter layer: `Adapter/` is the one folder allowed to import the module.
    /// It was written as a single file when `CoreHolder` was the only adapter; the notification
    /// plumbing needs the same access, and a directory is what the rule always meant.
    private static let bridgeFolder = "Adapter"

    private static func isAdapter(_ name: String) -> Bool {
        name.hasPrefix("\(bridgeFolder)/")
    }

    /// `.../iosApp/iosApp.xcodeproj`'s parent, found from this file rather than from the cwd.
    private static var projectDirectory: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // iosAppTests
            .deletingLastPathComponent()   // iosApp
    }

    /// Every Swift file under a folder, subfolders included: a first version scanned only the
    /// top level, which quietly exempted `Adapter/`, the one directory that is allowed to
    /// import the framework, and therefore the one a mistake would hide in.
    private static func swiftFiles(in directory: String) throws -> [String] {
        let root = projectDirectory.appendingPathComponent(directory)
        guard let walk = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else {
            return []
        }
        var names: [String] = []
        for case let url as URL in walk where url.pathExtension == "swift" {
            names.append(url.path.replacingOccurrences(of: root.path + "/", with: ""))
        }
        return names.sorted()
    }

    private static func contents(_ directory: String, _ name: String) throws -> String {
        try String(contentsOf: projectDirectory.appendingPathComponent(directory).appendingPathComponent(name), encoding: .utf8)
    }

    @Test func onlyTheBridgeImportsTheKotlinFramework() throws {
        let appFiles = try Self.swiftFiles(in: "iosApp")
        #expect(!appFiles.isEmpty, "the scan found no app sources; the guard would pass vacuously")
        #expect(appFiles.contains("Adapter/CoreHolder.swift"), "the scan reaches the adapter folder")

        var offenders: [String] = []
        for name in appFiles where !Self.isAdapter(name) {
            if try Self.contents("iosApp", name).contains("import core") { offenders.append(name) }
        }

        #expect(
            offenders.isEmpty,
            "the Kotlin framework is the adapter's only; a screen that imports it is a review defect: \(offenders)"
        )
    }

    /// The project lists folders, not files (Xcode 16 and later synchronise a directory into a
    /// target). That is what makes adding a Swift file free, and what this asserts, so a
    /// hand-edit that quietly switches back to an explicit file list fails here rather than
    /// leaving a new screen out of the build.
    @Test func everySourceFolderIsSynchronisedWithItsTarget() throws {
        let project = try String(
            contentsOf: Self.projectDirectory.appendingPathComponent("iosApp.xcodeproj/project.pbxproj"),
            encoding: .utf8
        )
        #expect(
            project.contains("PRODUCT_BUNDLE_IDENTIFIER = de.arjmandi.nanobot.companion;"),
            "the app's bundle id is the placeholder the owner will confirm",
        )
        #expect(!project.contains("sourcecode.swift"), "the project references folders, not individual Swift files")

        for folder in ["iosApp", "iosAppTests", "iosAppUITests"] {
            #expect(!(try Self.swiftFiles(in: folder)).isEmpty, "\(folder) has sources to build")
            #expect(
                project.contains("path = \(folder);"),
                "\(folder) is one of the project's synchronised folders"
            )
        }
        #expect(project.contains("PBXFileSystemSynchronizedRootGroup"), "the folders are the membership, so nothing can be forgotten")
    }
}
