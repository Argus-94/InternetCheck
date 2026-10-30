package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    @Test
    fun quietHoursDaytimeWindowAndBoundaries() {
        assertFalse(AlertPolicy.quietNow(true, 9 * 60, 17 * 60, 8 * 60 + 59))
        assertTrue(AlertPolicy.quietNow(true, 9 * 60, 17 * 60, 9 * 60))
        assertTrue(AlertPolicy.quietNow(true, 9 * 60, 17 * 60, 12 * 60))
        assertTrue(AlertPolicy.quietNow(true, 9 * 60, 17 * 60, 16 * 60 + 59))
        assertFalse(AlertPolicy.quietNow(true, 9 * 60, 17 * 60, 17 * 60))
    }

    @Test
    fun quietHoursNightWindowAndBoundaries() {
        assertTrue(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 22 * 60))
        assertTrue(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 23 * 60 + 59))
        assertTrue(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 0))
        assertTrue(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 6 * 60 + 59))
        assertFalse(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 7 * 60))
        assertFalse(AlertPolicy.quietNow(true, 22 * 60, 7 * 60, 21 * 60 + 59))
    }

    @Test
    fun quietHoursEqualBoundsAndDisabledAreAlwaysOff() {
        for (minute in listOf(0, 1, 12 * 60, 23 * 60 + 59)) {
            assertFalse(AlertPolicy.quietNow(true, 10 * 60, 10 * 60, minute))
            assertFalse(AlertPolicy.quietNow(false, 10 * 60, 10 * 60, minute))
            assertFalse(AlertPolicy.quietNow(false, 22 * 60, 7 * 60, minute))
        }
    }

    @Test
    fun repeatAllowedCoversEveryBooleanCombination() {
        for (alerts in listOf(false, true)) {
            for (repeat in listOf(false, true)) {
                for (quiet in listOf(false, true)) {
                    for (usable in listOf(false, true)) {
                        val expected = alerts && repeat && !quiet && usable
                        assertEquals(
                            "alerts=$alerts repeat=$repeat quiet=$quiet usable=$usable",
                            expected,
                            AlertPolicy.repeatAllowed(alerts, repeat, quiet, usable)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun channelKeyHasStableDirectionPrefixAndSha256Digest() {
        val up = AlertPolicy.channelKey("default", loud = false, vibrate = false, up = true)
        val down = AlertPolicy.channelKey("default", loud = false, vibrate = false, up = false)

        assertEquals(up, AlertPolicy.channelKey("default", loud = false, vibrate = false, up = true))
        assertTrue(up.startsWith("alert_v2_"))
        assertTrue(down.startsWith("lost_v2_"))
        assertEquals(73, up.length)
        assertEquals(72, down.length)
        assertNotEquals(up, down)
        assertTrue(up.substringAfter("alert_v2_").matches(Regex("[0-9a-f]{64}")))
        assertTrue(down.substringAfter("lost_v2_").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun channelKeyDistinguishesSilentDefaultAndCustomSounds() {
        val silent = AlertPolicy.channelKey("silent", loud = false, vibrate = false, up = true)
        val defaultSound = AlertPolicy.channelKey("default", loud = false, vibrate = false, up = true)
        val custom = AlertPolicy.channelKey("content://settings/custom_alarm", loud = false, vibrate = false, up = true)

        assertNotEquals(silent, defaultSound)
        assertNotEquals(defaultSound, custom)
        assertNotEquals(silent, custom)
    }

    @Test
    fun channelKeyDistinguishesLoudAndVibrateModes() {
        val base = AlertPolicy.channelKey("default", loud = false, vibrate = false, up = true)
        val loud = AlertPolicy.channelKey("default", loud = true, vibrate = false, up = true)
        val vibrate = AlertPolicy.channelKey("default", loud = false, vibrate = true, up = true)
        val loudAndVibrate = AlertPolicy.channelKey("default", loud = true, vibrate = true, up = true)

        assertNotEquals(base, loud)
        assertNotEquals(base, vibrate)
        assertNotEquals(base, loudAndVibrate)
        assertNotEquals(loud, vibrate)
        assertNotEquals(loud, loudAndVibrate)
        assertNotEquals(vibrate, loudAndVibrate)
    }
}
