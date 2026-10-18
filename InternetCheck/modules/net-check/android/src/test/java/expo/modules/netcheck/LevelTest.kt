package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelTest {
    @Test fun downloadBoundaries() {
        assertEquals(Level.NONE, Level.fromSpeed(9_999L))
        assertEquals(Level.WEAK, Level.fromSpeed(10_000L))
        assertEquals(Level.WEAK, Level.fromSpeed(99_999L))
        assertEquals(Level.MEDIUM, Level.fromSpeed(100_000L))
        assertEquals(Level.MEDIUM, Level.fromSpeed(399_999L))
        assertEquals(Level.GOOD, Level.fromSpeed(400_000L))
    }

    @Test fun uploadBoundaries() {
        assertEquals(Level.NONE, Level.fromUp(9_999L))
        assertEquals(Level.WEAK, Level.fromUp(10_000L))
        assertEquals(Level.WEAK, Level.fromUp(49_999L))
        assertEquals(Level.MEDIUM, Level.fromUp(50_000L))
        assertEquals(Level.MEDIUM, Level.fromUp(149_999L))
        assertEquals(Level.GOOD, Level.fromUp(150_000L))
    }

    @Test fun combineTakesWorstDirectionWhenUploadCounts() {
        assertEquals(Level.NONE, Level.combine(Level.GOOD, 5_000L, true))
        assertEquals(Level.WEAK, Level.combine(Level.WEAK, 900_000L, true))
        assertEquals(Level.MEDIUM, Level.combine(Level.MEDIUM, 900_000L, true))
    }

    @Test fun combineIgnoresUploadWhenNotCounted() {
        assertEquals(Level.GOOD, Level.combine(Level.GOOD, 5_000L, false))
    }

    @Test fun combineIgnoresUnmeasuredUpload() {
        assertEquals(Level.GOOD, Level.combine(Level.GOOD, -1L, true))
    }

    @Test fun onlyMediumAndAboveIsUsable() {
        assertEquals(false, Level.usable(Level.WEAK))
        assertEquals(true, Level.usable(Level.MEDIUM))
        assertEquals(true, Level.usable(Level.GOOD))
    }
}
