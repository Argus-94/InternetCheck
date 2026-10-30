package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val samplesFile get() = File(context.filesDir, "samples.csv")
    private val eventsFile get() = File(context.filesDir, "events.csv")

    @Before
    fun setUp() {
        Store.reset(context)
    }

    private fun sample(time: Long) = ProbeResult(
        level = Level.GOOD,
        speedBps = 500_000L,
        latencyMs = 20,
        bytes = 1L,
        transport = "wifi",
        time = time,
        durationMs = 10,
        host = "host",
        error = "",
        upBps = 100_000L,
        net = "Wi-Fi"
    )

    @Test
    fun firstNewSampleTrimsOldSamples() {
        val old = System.currentTimeMillis() - 15L * 24L * 3600L * 1000L
        samplesFile.writeText("$old,5,500000,100000,20,wifi,old\n")

        Store.addSample(context, sample(System.currentTimeMillis()))

        val text = samplesFile.readText()
        assertFalse(text.contains(",old"))
        assertEquals(1, Store.stats(context, 24 * 30).getInt("checks"))
    }

    @Test
    fun eventOnlyTrimKeepsLastBoundaryEvent() {
        val now = System.currentTimeMillis()
        val boundary = now - 15L * 24L * 3600L * 1000L
        eventsFile.writeText("${boundary - 1},0\n${boundary},1\n")

        Store.addEvent(context, now, true)

        val lines = eventsFile.readLines()
        assertEquals(2, lines.size)
        assertEquals("$boundary,1", lines[0])
        assertEquals("$now,1", lines[1])
    }

    @Test
    fun resetRemovesOldDataAndResetsTrimCounters() {
        Store.addSample(context, sample(System.currentTimeMillis()))
        Store.addEvent(context, System.currentTimeMillis(), false)

        Store.reset(context)

        assertFalse(samplesFile.exists())
        assertFalse(eventsFile.exists())
        assertEquals(0, Store.stats(context, 24).getInt("checks"))

        Store.addSample(context, sample(System.currentTimeMillis()))
        assertEquals(1, Store.stats(context, 24).getInt("checks"))
    }

    @Test
    fun malformedLineDoesNotHideValidTail() {
        val now = System.currentTimeMillis()
        samplesFile.writeText(
            "${now - 2},5,500000,100000,20,wifi,first\n" +
                "not-a-time,broken,broken,broken,broken,broken,broken\n" +
                "$now,5,500000,100000,20,wifi,last\n"
        )

        val stats = Store.stats(context, 24)

        assertEquals(2, stats.getInt("checks"))
        assertTrue(samplesFile.readText().contains(",last"))
    }
}
