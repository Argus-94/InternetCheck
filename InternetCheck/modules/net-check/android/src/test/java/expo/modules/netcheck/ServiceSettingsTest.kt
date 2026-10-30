package expo.modules.netcheck

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceSettingsTest {
    private lateinit var controller: ServiceController<MonitorService>
    private lateinit var svc: MonitorService
    private fun set(name: String, value: Any?) = MonitorService::class.java.getDeclaredField(name).run {
        isAccessible = true; set(svc, value)
    }
    private fun get(name: String): Any? = MonitorService::class.java.getDeclaredField(name).run {
        isAccessible = true; get(svc)
    }
    private fun applySettings() = MonitorService::class.java.getDeclaredMethod("applySettings", Boolean::class.javaPrimitiveType).run {
        isAccessible = true; invoke(svc, false)
    }
    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", 0).edit().clear().commit()
        Notifier.clearAlert()
        controller = Robolectric.buildService(MonitorService::class.java).create()
        svc = controller.get()
    }
    @After fun teardown() { controller.destroy() }

    @Test fun turningRepeatOffCancelsExistingRepeat() {
        set("stableUsable", true)
        set("repeating", true)
        Prefs.setRepeat(svc, false)
        applySettings()
        assertEquals(false, get("repeating"))
    }
    @Test fun turningAlertsOffAlsoCancelsRepeat() {
        set("stableUsable", true)
        set("repeating", true)
        Prefs.setAlerts(svc, false)
        applySettings()
        assertEquals(false, get("repeating"))
    }
    private fun activateAlert(up: Boolean) {
        Notifier::class.java.getDeclaredField("alert").run {
            isAccessible = true
            set(null, Notifier.AlertState(up, Level.GOOD, "event", "text", System.currentTimeMillis() + 60_000))
        }
        set("notificationChannel", "test_alert_channel")
    }
    @Test fun turningUpAlertsOffDismissesExistingUpEventWithoutStoppingService() {
        activateAlert(true)
        Prefs.setAlerts(svc, false)
        applySettings()
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel("test_alert_channel", false))
        assertTrue(MonitorService.running)
    }
    @Test fun turningLostAlertsOffDismissesExistingLostEvent() {
        activateAlert(false)
        Prefs.setAlertsLost(svc, false)
        applySettings()
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel("test_alert_channel", false))
    }
    @Test fun disablingUpEventsDoesNotDismissAllowedLostEvent() {
        activateAlert(false)
        Prefs.setAlerts(svc, false)
        Prefs.setAlertsLost(svc, true)
        applySettings()
        assertEquals("test_alert_channel", Notifier.statusChannel("test_alert_channel", false))
    }
    @Test fun turningFastWatchOffExitsQuickMode() {
        set("lastResult", ProbeResult(Level.NONE, 0, 50, 1000, "wifi", 1, 10, "test", ""))
        set("quickMode", true)
        Prefs.setFastWatch(svc, false)
        applySettings()
        assertEquals(false, get("quickMode"))
        assertFalse(Live.quickMode)
    }
    @Test fun intervalChangeReplacesOldSchedule() {
        Prefs.setSaving(svc, false, 0, false)
        Prefs.setIntervalSec(svc, 120)
        set("lastResult", ProbeResult(Level.GOOD, 500_000, 50, 1000, "wifi", 1, 10, "test", "", 200_000))
        set("nextAt", System.currentTimeMillis() + 900_000)
        val before = System.currentTimeMillis()
        applySettings()
        val next = get("nextAt") as Long
        assertTrue("next=$next before=$before", next in (before + 120_000)..(System.currentTimeMillis() + 120_000))
    }
}
