package expo.modules.netcheck

import org.junit.Assert.*
import org.junit.Test

class TargetSettingsTest {
    private fun result(down: Long = 500_000, up: Long = 200_000, transport: String = "wifi", offline: Boolean = false) =
        ProbeResult(if (offline) Level.OFFLINE else Level.combine(Level.fromSpeed(down), up, true),
            down, 30, 1000, transport, 1, 10, "test", "", up)

    @Test fun standardAndGoodThresholds() {
        for (down in listOf(0L, 9_999L, 10_000L, 99_999L, 100_000L, 399_999L, 400_000L)) {
            for (up in listOf(-1L, 0L, 9_999L, 10_000L, 49_999L, 50_000L, 149_999L, 150_000L)) {
                for (countUp in listOf(false, true)) for (mode in 0..1) {
                    val quality = Level.combine(Level.fromSpeed(down), up, countUp)
                    val expected = quality >= if (mode == 0) Level.MEDIUM else Level.GOOD
                    assertEquals("down=$down up=$up mode=$mode count=$countUp", expected,
                        Target.accepts(result(down, up), mode, 400, 100, false, countUp))
                }
            }
        }
    }

    @Test fun wifiOnlyAllowsWifiAndEthernetNotCellOrVpn() {
        for (transport in listOf("wifi", "eth", "cell", "vpn", "none")) {
            for (mode in 0..2) assertEquals(transport == "wifi" || transport == "eth",
                Target.accepts(result(transport = transport), mode, 400, 100, true, true))
        }
    }

    @Test fun customUploadMustBeMeasuredWhenRequired() {
        assertFalse(Target.accepts(result(up = -1), 2, 400, 100, false, true))
        assertTrue(Target.accepts(result(up = -1), 2, 400, 0, false, true))
        assertTrue(Target.accepts(result(up = -1), 2, 400, 100, false, false))
    }

    @Test fun customThresholdDoesNotUseBuiltInUploadMinimum() {
        assertTrue(Target.accepts(result(down = 20_000, up = 5_000), 2, 10, 5, false, true))
        assertFalse(Target.accepts(result(down = 20_000, up = 4_999), 2, 10, 5, false, true))
        assertFalse(Target.accepts(result(down = 19_999, up = 5_000), 2, 20, 5, false, true))
    }

    @Test fun currentUploadSettingOverridesOldCombinedLevel() {
        val old = result(up = 0)
        assertEquals(Level.NONE, old.level)
        assertTrue(Target.accepts(old, 0, 400, 100, false, false))
        assertFalse(Target.accepts(old, 0, 400, 100, false, true))
    }

    @Test fun offlineNeverUsable() {
        for (mode in 0..2) assertFalse(Target.accepts(result(offline = true), mode, 10, 0, false, false))
    }
}
