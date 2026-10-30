package expo.modules.netcheck

import org.junit.Assert.*
import org.junit.Test

class NotificationTrafficTextTest {
    @Test fun unitsStayWithNumbers() {
        assertEquals("556\u00a0КБ/с", NotificationTrafficText.speed(556000))
        assertEquals("6.6\u00a0МБ/с", NotificationTrafficText.speed(6600000))
        assertEquals("6.5\u00a0МБ/с", NotificationTrafficText.speed(6500000))
    }

    @Test fun emptyAndIdleAreDifferent() {
        assertEquals("—", NotificationTrafficText.speed(-1))
        assertEquals("0\u00a0Б/с", NotificationTrafficText.speed(0))
    }

    @Test fun roundsAtUnitBoundaryWithoutThousandKilobytes() {
        assertEquals("999\u00a0Б/с", NotificationTrafficText.speed(999))
        assertEquals("1.0\u00a0КБ/с", NotificationTrafficText.speed(1000))
        assertEquals("1.0\u00a0МБ/с", NotificationTrafficText.speed(999999))
        assertEquals("1.0\u00a0ГБ/с", NotificationTrafficText.speed(999999999))
    }

    @Test fun eachDirectionIsIndependentAndConnectionIsNotInSpeedLine() {
        val rates = RealtimeTraffic.Rates(556000, 6600000)
        assertEquals("↓ 556\u00a0КБ/с", NotificationTrafficText.down(rates))
        assertEquals("↑ 6.6\u00a0МБ/с", NotificationTrafficText.up(rates))
        assertFalse(NotificationTrafficText.fallback(rates).contains("VPN"))
    }

    @Test fun extremelyLargeValuesStayCompact() {
        assertTrue(NotificationTrafficText.speed(Long.MAX_VALUE).length <= 11)
        assertEquals("1.0\u00a0ТБ/с", NotificationTrafficText.speed(1000000000000L))
    }
}
