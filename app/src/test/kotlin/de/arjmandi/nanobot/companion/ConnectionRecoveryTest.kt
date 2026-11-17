package de.arjmandi.nanobot.companion

import de.arjmandi.nanobot.companion.states.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two ways a phone asks for a socket it lost: the banner's "Try again" and coming back to the
 * foreground. The shell's states decide what the banner says; this decides whether a handshake is
 * asked for at all, and the rule is one at a time: a second bootstrap racing the first is how a
 * single-use token gets burned twice.
 */
class ConnectionRecoveryTest {

    private class Counts {
        var connects = 0
        var reloads = 0
    }

    @Test
    fun aRetryReconnectsAndThenReadsAgain() = runTest {
        val counts = Counts()
        val model = ConnectionRecovery(reconnect = { counts.connects++; true }, scope = this)
        model.reload = { counts.reloads++ }

        model.retry(ConnectionStatus.Failed)
        runCurrent()

        assertEquals("the button asks the core for a new socket", 1, counts.connects)
        assertEquals("and the reads run on the token the new handshake minted", 1, counts.reloads)
    }

    @Test
    fun aFailedRetryReadsNothing() = runTest {
        val counts = Counts()
        val model = ConnectionRecovery(reconnect = { counts.connects++; false }, scope = this)
        model.reload = { counts.reloads++ }

        model.retry(ConnectionStatus.Failed)
        runCurrent()

        assertEquals(1, counts.connects)
        assertEquals("a handshake that failed minted no token to read with", 0, counts.reloads)
    }

    @Test
    fun aSecondTapDoesNotRaceTheFirstConnect() = runTest {
        val gate = CompletableDeferred<Unit>()
        val counts = Counts()
        val model = ConnectionRecovery(reconnect = { counts.connects++; gate.await(); true }, scope = this)
        model.reload = { counts.reloads++ }

        model.retry(ConnectionStatus.Failed)
        runCurrent()
        model.retry(ConnectionStatus.Failed)
        runCurrent()

        assertEquals(1, counts.connects)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, counts.reloads)
    }

    @Test
    fun comingBackToTheForegroundReconnectsASocketThatDropped() = runTest {
        var connects = 0
        val model = ConnectionRecovery(reconnect = { connects++; true }, scope = this)

        model.resumed(ConnectionStatus.Offline, everConnected = true)
        runCurrent()
        assertEquals("the socket died while the app was in the background", 1, connects)

        model.resumed(ConnectionStatus.Failed, everConnected = true)
        runCurrent()
        assertEquals("a backgrounded socket that failed is the same case", 2, connects)
    }

    @Test
    fun aPhoneThatNeverConnectedStaysWhereItIs() = runTest {
        var connects = 0
        val model = ConnectionRecovery(reconnect = { connects++; true }, scope = this)

        model.resumed(ConnectionStatus.Idle, everConnected = false)
        runCurrent()

        assertEquals("onboarding is not a socket to recover", 0, connects)
    }

    @Test
    fun aForegroundKeepsASocketThatIsUp() = runTest {
        var connects = 0
        val model = ConnectionRecovery(reconnect = { connects++; true }, scope = this)

        model.resumed(ConnectionStatus.Open, everConnected = true)
        runCurrent()

        assertEquals("nothing to recover on a socket that is up", 0, connects)
    }

    /** The button is drawn only under a banner that says the socket is lost; it acts on that. */
    @Test
    fun theButtonAsksForASocketWhateverTheStateSays() = runTest {
        var connects = 0
        val model = ConnectionRecovery(reconnect = { connects++; true }, scope = this)

        model.retry(ConnectionStatus.Open)
        runCurrent()

        assertEquals(1, connects)
    }

    @Test
    fun aConnectAlreadyRunningIsNotFought() = runTest {
        var connects = 0
        val model = ConnectionRecovery(reconnect = { connects++; true }, scope = this)

        model.resumed(ConnectionStatus.Connecting, everConnected = true)
        model.retry(ConnectionStatus.Connecting)
        runCurrent()

        assertEquals("the answer to the connect in flight is already coming", 0, connects)
    }
}
