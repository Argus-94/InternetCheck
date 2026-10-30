package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the network-safety and accounting audit fixes. */
class NetworkRegressionTest {
    @Test fun ping204AcceptsOnlyNoContent() {
        assertTrue(Quick.acceptsPing204(204))
        assertFalse(Quick.acceptsPing204(200))
        assertFalse(Quick.acceptsPing204(301))
        assertFalse(Quick.acceptsPing204(304))
    }

    @Test fun quickTrafficEstimateIncludesEveryAttempt() {
        assertEquals(0L, Quick.estimatedTraffic(0))
        assertEquals(4_500L, Quick.estimatedTraffic(3))
        assertEquals(6_000L, Quick.estimatedTraffic(4))
    }

    @Test fun negativeAttemptCountCannotReduceTrafficEstimate() {
        assertEquals(0L, Quick.estimatedTraffic(-1))
    }
}
