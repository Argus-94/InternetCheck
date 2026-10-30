package expo.modules.netcheck

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 28, 34], manifest = Config.NONE)
class SoundTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
        }
        Notifier.clearAlert()
    }

    @Test
    fun defaultSilentAndCustomUriMatrixPreservesSoundAndVibration() {
        val settings = listOf("" to Settings.System.DEFAULT_NOTIFICATION_URI, "silent" to null,
            "content://media/internal/audio/media/42" to android.net.Uri.parse("content://media/internal/audio/media/42"))
        for ((setting, expectedSound) in settings) {
            for (loud in listOf(false, true)) for (vibrate in listOf(false, true)) {
                Prefs.setSound(context, setting, "test")
                Prefs.setLoud(context, loud)
                Prefs.setVibrate(context, vibrate)
                val id = Notifier.ensureAlertChannel(context)
                if (Build.VERSION.SDK_INT < 26) {
                    continue
                }
                val channel = manager.getNotificationChannel(id)
                assertNotNull(channel)
                assertEquals(expectedSound, channel.sound)
                assertEquals(vibrate, channel.shouldVibrate())
                assertEquals(expectedSound != null && loud,
                    channel.audioAttributes?.usage == AudioAttributes.USAGE_ALARM)
                assertFalse("Alarm volume must not request a DND bypass", channel.canBypassDnd())
            }
        }
    }

    @Test
    fun invalidAndWhitespaceSoundSettingsFallBackToDefault() {
        if (Build.VERSION.SDK_INT < 26) return
        Prefs.setSound(context, "  not-a-provider  ", "bad")
        val channel = manager.getNotificationChannel(Notifier.ensureAlertChannel(context))
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, channel.sound)
        Prefs.setSound(context, "  silent  ", "muted")
        val muted = manager.getNotificationChannel(Notifier.ensureAlertChannel(context))
        assertNull(muted.sound)
    }

    @Test
    fun notificationAvailabilityIsExplicitAndDoesNotDependOnSoundChoice() {
        Prefs.setSound(context, "silent", "muted")
        assertTrue(Notifier.notificationsEnabled(context))
        Notifier.ensureChannels(context)
        if (Build.VERSION.SDK_INT < 26) return
        assertNotNull(manager.getNotificationChannel(Notifier.CH_STATUS))
        assertFalse(manager.getNotificationChannel(Notifier.CH_STATUS).shouldVibrate())
    }

    @Test
    fun bothDirectionsHaveIndependentChannelsOnChannelApis() {
        if (Build.VERSION.SDK_INT < 26) return
        Prefs.setSound(context, "content://media/internal/audio/media/42", "test")
        val up = manager.getNotificationChannel(Notifier.ensureAlertChannel(context, true))
        val down = manager.getNotificationChannel(Notifier.ensureAlertChannel(context, false))
        assertNotNull(up)
        assertNotNull(down)
        assertTrue(up.id != down.id)
        assertEquals(up.sound, down.sound)
        assertEquals(up.audioAttributes.usage, down.audioAttributes.usage)
    }

    @Test
    fun alertStateDurationReturnsToNormalStatus() {
        val field = Notifier::class.java.getDeclaredField("alert")
        field.isAccessible = true
        field.set(null, Notifier.AlertState(true, Level.GOOD, "title", "text", System.currentTimeMillis() - 1))
        assertEquals(Notifier.CH_STATUS, Notifier.statusChannel("alert", false))
    }

    @Test
    fun repeatPolicyRequiresAlertsRepeatUsableAndNonQuiet() {
        for (alerts in listOf(false, true)) for (repeat in listOf(false, true))
            for (quiet in listOf(false, true)) for (usable in listOf(false, true)) {
                assertEquals(alerts && repeat && !quiet && usable,
                    AlertPolicy.repeatAllowed(alerts, repeat, quiet, usable))
            }
    }
}
