package expo.modules.netcheck

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 34], manifest = Config.NONE)
class CompactNotificationTest {
    private lateinit var ctx: Context
    private lateinit var nm: NotificationManager

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        Notifier.clearAlert()
        Live.phase = Live.IDLE
        MonitorService.running = false
    }

    private fun status(fresh: Boolean = false): Notification {
        Notifier.ensureChannels(ctx)
        return Notifier.buildStatus(ctx, null, false,
            Notifier.Info(0, null, 0, false, 0, connection = "Wi-Fi"),
            if (fresh) Notifier.ensureAlertChannel(ctx) else Notifier.CH_STATUS, fresh)
    }

    @Test fun compactIsDefaultIncludingExistingPreferences() {
        Prefs.setVibrate(ctx, false)
        assertTrue(Prefs.compactNotification(ctx))
    }

    @Test fun preferenceCanBeChangedAndRestored() {
        Prefs.setCompactNotification(ctx, false)
        assertFalse(Prefs.compactNotification(ctx))
        Prefs.setCompactNotification(ctx, true)
        assertTrue(Prefs.compactNotification(ctx))
    }

    @Test fun bothLayoutsStayCompactByDefault() {
        val n = status()
        assertEquals(R.layout.nc_notification_compact, n.contentView.layoutId)
        assertEquals(R.layout.nc_notification_compact, n.bigContentView.layoutId)
        assertNotSame(n.contentView, n.bigContentView)
        assertNotEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(2, n.actions.size)
    }

    @Test fun oldDetailedPreferenceCannotExpandStatus() {
        Prefs.setCompactNotification(ctx, false)
        val n = status()
        assertEquals(R.layout.nc_notification_compact, n.contentView.layoutId)
        assertEquals(R.layout.nc_notification_compact, n.bigContentView.layoutId)
    }

    @Test fun compactModeDoesNotDisableFreshSignalsOrActions() {
        val n = status(true)
        assertEquals(0, n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(2, n.actions.size)
        assertEquals(R.layout.nc_notification_compact, n.bigContentView.layoutId)
    }

    @Test fun standaloneTestIsNotBigTextByDefault() {
        Notifier.testAlert(ctx)
        val n = shadowOf(nm).getNotification(Notifier.ID_STATUS)
        assertNotNull(n)
        assertNotEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals("Проверка оповещения", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.actions.isNotEmpty())
    }

    @Test fun standaloneTestIgnoresOldDetailedPreference() {
        Prefs.setCompactNotification(ctx, false)
        Notifier.testAlert(ctx)
        val n = shadowOf(nm).getNotification(Notifier.ID_STATUS)
        assertNotEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
    }
}
