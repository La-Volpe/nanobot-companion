package de.arjmandi.nanobot.companion.automations

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automations page is a list plus four acts. These tests describe what it does with the
 * gateway's answers: it never flips a row on its own, and it never hides a refusal.
 */
class AutomationsViewModelTest {

    private class FakeDriver(var rows: List<AutomationRow>, var refusal: String? = null) : AutomationsDriving {
        val acts = mutableListOf<Pair<AutomationAct, String>>()
        var loads = 0
        var loadFails = false

        override suspend fun load(): List<AutomationRow>? {
            loads += 1
            return if (loadFails) null else rows
        }

        override suspend fun act(act: AutomationAct, id: String): String? {
            acts += act to id
            if (refusal != null) return refusal
            when (act) {
                AutomationAct.Delete -> rows = rows.filterNot { it.id == id }
                AutomationAct.Pause -> rows = rows.map { if (it.id == id) it.copy(enabled = false) else it }
                AutomationAct.Resume -> rows = rows.map { if (it.id == id) it.copy(enabled = true) else it }
                AutomationAct.Run -> Unit
            }
            return null
        }
    }

    private fun row(id: String, enabled: Boolean = true, system: Boolean = false) = AutomationRow(
        id = id,
        name = "Morning brief",
        schedule = "0 8 * * *",
        detail = "Next · Fri 08:00",
        enabled = enabled,
        isSystem = system,
    )

    @Test
    fun `loading fills the list`() = runTest {
        val model = AutomationsViewModel(FakeDriver(listOf(row("a"), row("b"))))

        model.load()

        assertEquals(listOf("a", "b"), model.rows.map { it.id })
        assertTrue(model.loaded)
        assertNull(model.message)
    }

    @Test
    fun `an empty gateway is not a failure`() = runTest {
        val model = AutomationsViewModel(FakeDriver(emptyList()))

        model.load()

        assertTrue("the page must say 'no automations yet'", model.isEmpty)
        assertEquals(AutomationsPage.Empty, model.page)
        assertNull(model.message)
    }

    @Test
    fun `a failed read keeps the rows it had`() = runTest {
        val driver = FakeDriver(listOf(row("a")))
        val model = AutomationsViewModel(driver)
        model.load()

        driver.loadFails = true
        model.load()

        assertEquals(1, model.rows.size)
        assertTrue(model.message != null)
    }

    @Test
    fun `a page that has not been read says nothing about having none`() = runTest {
        val model = AutomationsViewModel(FakeDriver(listOf(row("a"))))

        assertEquals("before the read answers, the page is loading", AutomationsPage.Loading, model.page)
        assertTrue("so the empty copy must not be drawn", !model.isEmpty)
    }

    @Test
    fun `a failed refresh does not say the gateway has no automations`() = runTest {
        // The page loaded, and the gateway said there is nothing scheduled. Then a refresh failed:
        // "No automations yet" is a claim the failed read cannot make.
        val driver = FakeDriver(emptyList())
        val model = AutomationsViewModel(driver)
        model.load()
        assertEquals(AutomationsPage.Empty, model.page)

        driver.loadFails = true
        model.load()

        assertEquals(AutomationsPage.Failed, model.page)
        assertTrue("and the sentence the page already has is what says so", model.message != null)
    }

    @Test
    fun `the switch pauses and resumes`() = runTest {
        val driver = FakeDriver(listOf(row("a")))
        val model = AutomationsViewModel(driver)
        model.load()

        model.setEnabled("a", false)
        assertEquals(listOf(AutomationAct.Pause), driver.acts.map { it.first })
        assertEquals(false, model.rows.first().enabled)

        driver.acts.clear()
        model.setEnabled("a", true)
        assertEquals(listOf(AutomationAct.Resume), driver.acts.map { it.first })
        assertEquals(true, model.rows.first().enabled)
    }

    @Test
    fun `run now asks once and re-reads`() = runTest {
        val driver = FakeDriver(listOf(row("a")))
        val model = AutomationsViewModel(driver)
        model.load()
        val loadsAfterOpen = driver.loads

        model.runNow("a")

        assertEquals(listOf(AutomationAct.Run), driver.acts.map { it.first })
        assertEquals(loadsAfterOpen + 1, driver.loads)
    }

    @Test
    fun `delete removes the row only because the gateway says so`() = runTest {
        val model = AutomationsViewModel(FakeDriver(listOf(row("a"), row("b"))))
        model.load()

        model.delete("a")

        assertEquals(listOf("b"), model.rows.map { it.id })
    }

    @Test
    fun `a refusal is said out loud and the row stays`() = runTest {
        val driver = FakeDriver(listOf(row("heartbeat", enabled = false, system = true)))
        driver.refusal = "This job belongs to the gateway and cannot be changed from here."
        val model = AutomationsViewModel(driver)
        model.load()

        model.setEnabled("heartbeat", true)

        assertEquals(1, model.rows.size)
        assertEquals(driver.refusal, model.message)
    }
}
