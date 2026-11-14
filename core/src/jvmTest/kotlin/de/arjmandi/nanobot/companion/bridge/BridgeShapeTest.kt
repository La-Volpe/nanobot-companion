package de.arjmandi.nanobot.companion.bridge

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What Swift actually sees (C8.2). Not the docs, not the KMP guide: the Objective-C header
 * Kotlin/Swift interop generated for this framework, read back and asserted. Every claim a
 * Swift screen depends on is one assertion here, so a toolchain upgrade that changes the
 * shape fails a test rather than a Swift compile three tasks later.
 *
 * Two kinds of assertion live in this file on purpose:
 *
 *  - **the bridge's own**, which fail when [ObservableState] or the companion factory is
 *    deleted — those are tests;
 *  - **the record**, which state what Kotlin/Native does with `StateFlow`, enums and sealed
 *    interfaces *today*. Those lock the reason the bridge exists and pass without it; they
 *    are documentation with a tripwire, listed as such in the run report.
 *
 * The header is produced by `:core:linkDebugFrameworkIosSimulatorArm64`, which `jvmTest`
 * depends on, so the fast suite cannot pass with a stale or missing header.
 */
class BridgeShapeTest {

    private val header: Header = Header.read()

    @Test
    fun theHeaderWasGeneratedForThisRun() {
        assertTrue(header.declarations.isNotEmpty(), "the framework header parsed to nothing; every claim below would pass vacuously")
        assertNotNull(header.declaration("NanobotCore"), "the facade is in the header")
        assertNotNull(header.declaration("ObservableState"), "the bridge is in the header")
    }

    /**
     * The record, and the reason [ObservableState] exists: a `StateFlow` reaches Swift as an
     * untyped protocol whose `value` is `id`. A screen reading `core.connection.value` would
     * have to `as!` it, in every view, or read nothing.
     */
    @Test
    fun stateFlowArrivesAsAnUntypedProtocol() {
        val stateFlow = assertNotNull(header.declaration("Kotlinx_coroutines_coreStateFlow"))
        assertTrue(
            stateFlow.body.contains("@property (readonly) id _Nullable value"),
            "StateFlow.value is untyped in the generated header:\n${stateFlow.body}",
        )

        val core = assertNotNull(header.declaration("NanobotCore"))
        assertTrue(
            core.body.contains("- (void)connectWithCompletionHandler:"),
            "a suspend function arrives as a completion handler",
        )
        assertTrue(
            core.body.lines().any { it.contains("<CoreKotlinx_coroutines_coreStateFlow>") && it.contains("connection") },
            "the facade's connection flow is the untyped box the bridge closes:\n${core.body}",
        )
    }

    @Test
    fun theBridgeGivesSwiftATypedSubscribeThatCancels() {
        val observer = assertNotNull(
            header.declaration("ObservableState"),
            "the bridge is missing: Swift would have to cast every StateFlow value itself",
        )
        assertTrue(
            observer.body.contains("@interface CoreObservableState<T>"),
            "ObservableState is generic, so a Swift screen names its own state type:\n${observer.body}",
        )
        assertTrue(
            observer.body.lines().any { it.contains("- (CoreCancellable *)subscribeOnChange:") },
            "subscribe returns a cancellable, not a Job:\n${observer.body}",
        )
        assertTrue(
            observer.body.lines().any { it.contains("@property (readonly) T value") },
            "the current value keeps the observer's type:\n${observer.body}",
        )

        val cancellable = assertNotNull(header.declaration("Cancellable"))
        assertTrue(cancellable.body.contains("- (void)cancel"), "Swift cancels by calling cancel():\n${cancellable.body}")
    }

    /**
     * Enums are the one place Swift cannot use the Kotlin declaration as written: a Kotlin
     * enum arrives as a class whose cases are class properties, and the generated Swift names
     * are lowercased rather than camelCased. The eight copy keys are switched on by these
     * names, so they are asserted exactly.
     */
    @Test
    fun enumsArriveAsClassesWithLowercasedCaseNames() {
        val failure = assertNotNull(header.declaration("ConnectionFailure"))
        assertTrue(
            failure.body.contains("@interface CoreConnectionFailure : CoreKotlinEnum<CoreConnectionFailure *>"),
            "a Kotlin enum exports as a class over KotlinEnum:\n${failure.body}",
        )
        val declared = failure.body.lines()
            .filter { it.contains("@property (class, readonly) CoreConnectionFailure *") }
            .map { it.substringAfter("swift_name(\"").substringBefore("\")") }
        assertEquals(
            listOf(
                "authfailed", "tlsrejected", "unreachable", "timeout",
                "protocoltoonew", "missingcapability", "missingfield", "servererror",
            ),
            declared,
            "Swift switches on these names; the eight taxonomy entries are the eight cases",
        )
        assertTrue(failure.body.contains("copyKey"), "the copy key is readable from Swift:\n${failure.body}")
    }

    @Test
    fun sealedResultsArriveAsProtocolsWithOneClassPerCase() {
        val receipt = assertNotNull(header.declaration("SendReceipt"))
        assertTrue(receipt.body.contains("@protocol CoreSendReceipt"), "a sealed interface is an ObjC protocol:\n${receipt.body}")

        val accepted = assertNotNull(header.declaration("SendReceiptAccepted"))
        assertTrue(
            accepted.body.contains("@interface CoreSendReceiptAccepted : CoreBase <CoreSendReceipt>"),
            "each case is a class Swift can cast to:\n${accepted.body}",
        )

        val state = assertNotNull(header.declaration("ConnectionStateFailed"))
        assertTrue(
            state.body.contains("<CoreConnectionState>") &&
                state.body.contains("@property (readonly) CoreConnectionFailure *failure"),
            "the failure case carries the taxonomy as a typed property:\n${state.body}",
        )
    }

    @Test
    fun valueTypesArriveAsTheirOwnClassesNotAsBoxes() {
        val thread = assertNotNull(header.declaration("ThreadState"))
        assertTrue(
            thread.body.contains("@property (readonly) NSArray<CoreTurn *> *turns"),
            "lists arrive as typed NSArray, not as Kotlin boxes:\n${thread.body}",
        )

        val turn = assertNotNull(header.declaration("Turn"))
        assertTrue(
            turn.body.contains("- (CoreAnswerState *)answer") || turn.body.contains("@property (readonly) CoreAnswerState *answer"),
            "a nested value type keeps its own class:\n${turn.body}",
        )

        val composer = assertNotNull(header.declaration("ComposerState"))
        assertTrue(
            composer.body.contains("@property (readonly) CoreLimits *"), "an optional carries its own class:\n${composer.body}",
        )
    }

    /**
     * The bridge's suspend-visible surface: Swift must be able to `try await` a call and catch
     * an error instead of trapping. `@Throws` is what removes the fatal note from the header,
     * so the note's absence is the assertion.
     */
    @Test
    fun bridgeCallsSurfaceErrorsInsteadOfTrapping() {
        val core = assertNotNull(header.declaration("NanobotCore"))
        val connect = core.body.lines()
            .first { it.contains("- (void)connectWithCompletionHandler:") }
        assertTrue(
            connect.contains("completionHandler") && !core.body.contains("Other uncaught Kotlin exceptions are fatal"),
            "@Throws on the facade's suspend functions turns every throw into an NSError Swift can catch",
        )
    }

    @Test
    fun theCompanionFactoryKeepsSwiftOutOfKtorAndTheKeychain() {
        val companion = assertNotNull(
            header.declaration("Companion"),
            "the app needs one object that owns the Ktor client and the stored key",
        )
        assertTrue(companion.body.contains("@property (readonly) CoreNanobotCore *core"), "the facade is reachable from it:\n${companion.body}")
        assertTrue(companion.body.contains("- (void)close"), "the HTTP client has an owner that ends it:\n${companion.body}")

        val secrets = assertNotNull(header.declaration("CompanionBridgeKt"), "the factory is a top-level function")
        assertTrue(
            secrets.body.contains("+ (void)storeGatewaySecretSecret:(NSString *)secret") &&
                secrets.body.contains("+ (NSString * _Nullable)readGatewaySecret") &&
                secrets.body.contains("+ (void)forgetGatewaySecret"),
            "the stored key is reachable before anything is connected (D5):\n${secrets.body}",
        )

        val factory = assertNotNull(header.declaration("CompanionBridgeKt"), "the factory is a top-level function")
        assertTrue(
            factory.body.contains(
                "+ (CoreCompanion *)openCompanionBaseUrl:(NSString *)baseUrl secret:(NSString *)secret " +
                    "allowUnverifiedCertificate:(BOOL)allowUnverifiedCertificate",
            ),
            "one call builds deps, client and facade, and the trust switch is a parameter of it (C3.2):\n${factory.body}",
        )
    }

    // --- reading the generated header ---------------------------------------------------

    private class Declaration(val name: String, val body: String)

    private class Header(val text: String) {
        val declarations: Map<String, Declaration> = PATTERN.findAll(text)
            .associate { match ->
                match.groupValues[1] to Declaration(match.groupValues[1], match.groupValues[0])
            }

        fun declaration(name: String): Declaration? = declarations[name]

        companion object {
            /** One `swift_name` attribute plus the interface or protocol it introduces. */
            private val PATTERN = Regex(
                """__attribute__\(\(swift_name\("([A-Za-z0-9_]+)"\)\)\)\s*\n(@interface|@protocol)[^\n]*\n(?:.*?\n)?@end""",
                RegexOption.DOT_MATCHES_ALL,
            )

            fun read(): Header {
                val configured = System.getProperty("nanobot.bridgeHeader")
                assertNotNull(
                    configured,
                    "no bridge header path: the build sets -Dnanobot.bridgeHeader from :core:linkDebugFrameworkIosSimulatorArm64",
                )
                val file = File(configured)
                assertTrue(
                    file.isFile,
                    "the framework header is missing at ${file.path}; run ./gradlew :core:linkDebugFrameworkIosSimulatorArm64",
                )
                return Header(file.readText())
            }
        }
    }
}
