package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeTrafficTest {
    @Test fun firstSampleIsUnknownNotFakeZero() {
        assertEquals(RealtimeTraffic.Rates(), RealtimeTraffic().update(100, 200, 1000))
    }

    @Test fun measuresBothDirectionsUsingActualElapsedTime() {
        val meter = RealtimeTraffic()
        meter.update(1000, 2000, 1000)
        assertEquals(RealtimeTraffic.Rates(2000, 500), meter.update(4000, 2750, 2500))
    }

    @Test fun idleTrafficIsReallyZero() {
        val meter = RealtimeTraffic()
        meter.update(1000, 2000, 1000)
        assertEquals(RealtimeTraffic.Rates(0, 0), meter.update(1000, 2000, 2000))
    }

    @Test fun smallTransfersAreNotSuppressedAsNoise() {
        val meter = RealtimeTraffic()
        meter.update(0, 0, 1000)
        assertEquals(RealtimeTraffic.Rates(1, 2), meter.update(1, 2, 2000))
    }

    @Test fun unsupportedDirectionDoesNotHideTheOtherDirection() {
        val meter = RealtimeTraffic()
        meter.update(-1, 0, 1000)
        assertEquals(RealtimeTraffic.Rates(-1, 30), meter.update(-1, 30, 2000))
    }

    @Test fun resetCounterIsUnknownAndRecovers() {
        val meter = RealtimeTraffic()
        meter.update(5000, 6000, 1000)
        assertEquals(RealtimeTraffic.Rates(-1, -1), meter.update(100, 200, 2000))
        assertEquals(RealtimeTraffic.Rates(30, 40), meter.update(130, 240, 3000))
    }

    @Test fun unavailableCounterNeedsTwoValidSamplesToRecover() {
        val meter = RealtimeTraffic()
        meter.update(-1, -1, 1000)
        assertEquals(RealtimeTraffic.Rates(), meter.update(100, 200, 2000))
        assertEquals(RealtimeTraffic.Rates(10, 20), meter.update(110, 220, 3000))
    }

    @Test fun zeroOrReversedTimeDoesNotDivideByZero() {
        val meter = RealtimeTraffic()
        meter.update(0, 0, 1000)
        assertEquals(RealtimeTraffic.Rates(), meter.update(50, 50, 1000))
        assertEquals(RealtimeTraffic.Rates(), meter.update(100, 100, 900))
    }

    @Test fun wakingFromSleepDoesNotShowStaleAverage() {
        val meter = RealtimeTraffic()
        meter.update(0, 0, 1000)
        assertEquals(RealtimeTraffic.Rates(), meter.update(90000, 80000, 61000))
        assertEquals(RealtimeTraffic.Rates(1, 2), meter.update(90001, 80002, 62000))
    }

    @Test fun changingTrafficSourceResetsBaseline() {
        val meter = RealtimeTraffic()
        meter.update(1000, 2000, 1000, "interfaces:wlan0;network=wifi")
        assertEquals(
            RealtimeTraffic.Rates(),
            meter.update(9000, 12000, 2000, "interfaces:rmnet0;network=cell")
        )
        assertEquals(
            RealtimeTraffic.Rates(10, 20),
            meter.update(9010, 12020, 3000, "interfaces:rmnet0;network=cell")
        )
    }

    @Test fun fiveSecondBoundaryIsValidButSleepBeyondItIsUnknown() {
        val meter = RealtimeTraffic()
        meter.update(0, 0, 1000)
        assertEquals(RealtimeTraffic.Rates(2, 4), meter.update(10, 20, 6000))
        assertEquals(RealtimeTraffic.Rates(), meter.update(1000, 2000, 11001))
    }

    @Test fun formatsBytesKilobytesAndMegabytesNotBits() {
        assertEquals("—", RealtimeTraffic.speed(-1))
        assertEquals("0 Б/с", RealtimeTraffic.speed(0))
        assertEquals("999 Б/с", RealtimeTraffic.speed(999))
        assertEquals("1.0 КБ/с", RealtimeTraffic.speed(1000))
        assertEquals("12.5 КБ/с", RealtimeTraffic.speed(12500))
        assertEquals("1.0 МБ/с", RealtimeTraffic.speed(1000000))
        assertEquals("2.5 МБ/с", RealtimeTraffic.speed(2500000))
    }

    @Test fun connectionAndBothArrowsAreAlwaysVisible() {
        assertEquals("Wi‑Fi · ↓ 1.0 КБ/с · ↑ 0 Б/с",
            RealtimeTraffic.line("Wi‑Fi", RealtimeTraffic.Rates(1000, 0)))
        assertEquals("Нет сети · ↓ — · ↑ —",
            RealtimeTraffic.line("Нет сети", RealtimeTraffic.Rates()))
    }
}
