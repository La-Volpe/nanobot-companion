package de.arjmandi.nanobot.companion.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Everything the core cannot own for itself: time, secret storage, plain file storage,
 * the app's identity and the dispatchers an engine runs on. A test
 * composes it from fakes; each app composes it from [productionDeps].
 */
public class CoreDeps(
    public val clock: Clock,
    public val secureStore: SecureStore,
    public val files: FileStore,
    public val appInfo: AppInfo,
    public val dispatchers: CoreDispatchers,
)

/** The app's own identity, for Settings → Overview. Never the gateway's version. */
public interface AppInfo {
    public val version: String
    public val build: String
    public val platform: String
}

/**
 * Reduction runs on [compute]; everything that touches a UI happens on [main]. Passing them
 * in keeps engine tests off the wall clock and off the real main queue.
 */
public class CoreDispatchers(
    public val compute: CoroutineDispatcher,
    public val main: CoroutineDispatcher,
)
