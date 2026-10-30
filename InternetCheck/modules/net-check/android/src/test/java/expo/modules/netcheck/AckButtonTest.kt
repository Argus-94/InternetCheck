package expo.modules.netcheck

import android.app.Notification
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Кнопка «Принял» в уведомлении: не пропадает во время проверки и срабатывает через приёмник. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 34], manifest = Config.NONE)
class AckButtonTest {
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Notifier.clearAlert()
        Live.phase = Live.IDLE
        MonitorService.instance = null
        MonitorService.running = false
    }

    @After fun tearDown() {
        Notifier.clearAlert()
        Live.phase = Live.IDLE
    }

    private fun setAlert(up: Boolean, until: Long) {
        Notifier::class.java.getDeclaredField("alert").run {
            isAccessible = true
            set(null, Notifier.AlertState(up, Level.GOOD, "test", "test", until))
        }
    }

    private fun status(checking: Boolean): Notification {
        Notifier.ensureChannels(ctx)
        return Notifier.buildStatus(
            ctx, null, checking,
            Notifier.Info(0, null, 0, false, 0, connection = "Wi-Fi")
        )
    }

    private fun titles(n: Notification): List<String> = n.actions.map { it.title.toString() }

    @Test fun ackIsFirstButtonWhileAlertIsShown() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        assertEquals("Принял", titles(status(false))[0])
    }

    @Test fun ackStaysOnTheSamePlaceDuringLiveCheck() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        val idle = titles(status(false))
        Live.phase = Live.DOWN
        val live = titles(status(true))
        assertEquals(idle, live)
        assertEquals("Принял", live[0])
        assertFalse(live.contains("Выключить"))
    }

    @Test fun lostAlertHasNoAckButton() {
        setAlert(false, System.currentTimeMillis() + 60_000L)
        assertEquals("Проверить", titles(status(false))[0])
    }

    @Test fun ackButtonIsGoneAfterAcknowledgement() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        Notifier.clearAlert()
        assertEquals("Проверить", titles(status(false))[0])
    }

    @Test fun ackActionIsDeliveredToReceiverNotToService() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        val pi = status(false).actions[0].actionIntent
        val shadow = shadowOf(pi)
        assertTrue(shadow.isBroadcastIntent)
        assertEquals(AckReceiver::class.java.name, shadow.savedIntent.component?.className)
        assertEquals(MonitorService.ACTION_ACK, shadow.savedIntent.action)
    }

    @Test fun receiverAcknowledgesEvenWithoutRunningService() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        assertTrue(Notifier.ackPending())
        AckReceiver().onReceive(ctx, Intent(MonitorService.ACTION_ACK))
        assertFalse(Notifier.ackPending())
    }

    @Test fun receiverIgnoresForeignActions() {
        setAlert(true, System.currentTimeMillis() + 60_000L)
        AckReceiver().onReceive(ctx, Intent("some.other.ACTION"))
        assertTrue(Notifier.ackPending())
    }

    @Test fun expiredAlertIsNotPending() {
        setAlert(true, System.currentTimeMillis() - 1L)
        assertFalse(Notifier.ackPending())
    }
}

/** Плановые перерисовки уведомления не должны срывать нажатие на «Принял». */
class AckRefreshPolicyTest {
    @Test fun tickIsSkippedOnlyWhileAckIsShownAndRecentlyPosted() {
        assertTrue(AlertPolicy.skipTickRefresh(true, 13_999L, 9_000L, 5_000L))
        assertFalse(AlertPolicy.skipTickRefresh(true, 14_000L, 9_000L, 5_000L))
        assertFalse(AlertPolicy.skipTickRefresh(false, 9_500L, 9_000L, 5_000L))
    }

    @Test fun firstPostAndClockJumpsAreNeverSkipped() {
        assertFalse(AlertPolicy.skipTickRefresh(true, 1_000L, 0L, 5_000L))
        assertFalse(AlertPolicy.skipTickRefresh(true, 8_000L, 9_000L, 5_000L))
    }
}
