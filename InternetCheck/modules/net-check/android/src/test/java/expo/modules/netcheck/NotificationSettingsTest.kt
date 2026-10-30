package expo.modules.netcheck

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NotificationSettingsTest {
    private lateinit var ctx: Context
    private lateinit var nm: NotificationManager

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notificationChannels.forEach { nm.deleteNotificationChannel(it.id) }
        Notifier.clearAlert()
    }

    @Test fun bothEventsUseSelectedSoundAndVolume() {
        for (uri in listOf("", "silent", "content://media/internal/audio/media/42")) {
            for (loud in listOf(false, true)) for (vibrate in listOf(false, true)) {
                Prefs.setSound(ctx, uri, "test")
                Prefs.setLoud(ctx, loud)
                Prefs.setVibrate(ctx, vibrate)
                for (up in listOf(false, true)) {
                    val ch = nm.getNotificationChannel(Notifier.ensureAlertChannel(ctx, up))
                    val expected = when (uri) {
                        "" -> Settings.System.DEFAULT_NOTIFICATION_URI
                        "silent" -> null
                        else -> Uri.parse(uri)
                    }
                    assertEquals(expected, ch.sound)
                    assertEquals(vibrate, ch.shouldVibrate())
                    if (expected != null) assertEquals(
                        if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION,
                        ch.audioAttributes.usage
                    )
                }
            }
        }
    }

    @Test fun statusAndQuietHaveNoSoundOrVibration() {
        Notifier.ensureChannels(ctx)
        for (id in listOf(Notifier.CH_STATUS, Notifier.CH_QUIET)) {
            val ch = nm.getNotificationChannel(id)
            assertNull(ch.sound)
            assertFalse(ch.shouldVibrate())
        }
    }

    @Test fun changingSoundDoesNotDeletePreviouslyActiveChannel() {
        Prefs.setSound(ctx, "content://media/internal/audio/media/1", "one")
        val first = Notifier.ensureAlertChannel(ctx)
        Prefs.setSound(ctx, "content://media/internal/audio/media/2", "two")
        val second = Notifier.ensureAlertChannel(ctx)
        assertNotEquals(first, second)
        assertNotNull(nm.getNotificationChannel(first))
        assertNotNull(nm.getNotificationChannel(second))
    }

    @Test fun systemChannelChoiceIsPreserved() {
        val id = Notifier.ensureAlertChannel(ctx)
        val ch = nm.getNotificationChannel(id)
        assertEquals(id, Notifier.ensureAlertChannel(ctx))
        assertEquals(ch.sound, nm.getNotificationChannel(id).sound)
    }

    private fun setAlert(until: Long) {
        Notifier::class.java.getDeclaredField("alert").run {
            isAccessible = true
            set(null, Notifier.AlertState(true, Level.GOOD, "test", "test", until))
        }
    }

    @Test fun liveUpdatesKeepChannelButDoNotRestartSound() {
        Notifier.ensureChannels(ctx)
        val channel = Notifier.ensureAlertChannel(ctx)
        setAlert(System.currentTimeMillis() + 60_000)
        val info = Notifier.Info(0, null, 0, false, 0, connection = "Wi-Fi")
        val fresh = Notifier.buildStatus(ctx, null, false, info, channel, true)
        val update = Notifier.buildStatus(ctx, null, false, info, channel, false)
        assertEquals(channel, fresh.channelId)
        assertEquals(channel, update.channelId)
        assertEquals(0, fresh.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNotEquals(0, update.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun expiredAlertReturnsToStatusChannel() {
        setAlert(System.currentTimeMillis() - 1)
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel("alert_test", false))
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel(Notifier.CH_QUIET, false))
    }

    @Test fun acknowledgedAlertReturnsToStatusChannel() {
        setAlert(System.currentTimeMillis() + 60_000)
        Notifier.clearAlert()
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel("alert_test", false))
    }
}
