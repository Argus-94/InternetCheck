package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficTest {
    private val start = Traffic.Snap(0L, 0L, 0L, 0L, 0L)

    @Test fun otherAppsTrafficIsNetMinusOwn() {
        val busy = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 1000L)
        assertEquals(2_000_000L, Traffic.foreign(start, busy, false))
    }

    @Test fun uploadDirectionUsesTxCounters() {
        val busy = Traffic.Snap(0L, 100_000L, 0L, 600_000L, 1000L)
        assertEquals(500_000L, Traffic.foreign(start, busy, true))
    }

    @Test fun systemNoiseIsIgnored() {
        val quiet = Traffic.Snap(500_000L, 0L, 505_000L, 0L, 1000L)
        assertEquals(0L, Traffic.foreign(start, quiet, false))
    }

    @Test fun missingSnapshotMeansNoAdjustment() {
        val busy = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 1000L)
        assertEquals(0L, Traffic.foreign(null, busy, false))
        assertEquals(0L, Traffic.foreign(start, null, false))
    }

    @Test fun tooShortWindowIsIgnored() {
        val busy = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 100L)
        assertEquals(0L, Traffic.foreign(start, busy, false))
    }

    @Test fun counterResetIsIgnored() {
        val busy = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 1000L)
        val reset = Traffic.Snap(0L, 0L, 0L, 0L, 2000L)
        assertEquals(0L, Traffic.foreign(busy, reset, false))
    }
}
