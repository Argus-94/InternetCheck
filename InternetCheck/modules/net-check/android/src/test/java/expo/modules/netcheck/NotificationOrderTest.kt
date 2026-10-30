package expo.modules.netcheck

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 34], manifest = Config.NONE)
class NotificationOrderTest {
    private lateinit var ctx: Context
    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Notifier.clearAlert()
        Live.phase = Live.IDLE
        Notifier.ensureChannels(ctx)
    }

    private fun assertCompactOrder(checking: Boolean, result: ProbeResult?) {
        val n = Notifier.buildStatus(ctx, result, checking,
            Notifier.Info(0, null, 0, false, 0, trafficRates = RealtimeTraffic.Rates(1234, 567), connection = "Wi-Fi · VPN"))
        for (remote in arrayOf(n.contentView, n.bigContentView)) {
            assertEquals(R.layout.nc_notification_compact, remote.layoutId)
            val root = remote.apply(ctx, null) as ViewGroup
            val traffic = root.getChildAt(0) as ViewGroup
            assertNotNull(traffic.findViewById<TextView>(R.id.nc_traffic_down))
            assertNotNull(traffic.findViewById<TextView>(R.id.nc_traffic_up))
            assertEquals(R.id.nc_status, root.getChildAt(1).id)
            assertEquals(R.id.nc_quality, root.getChildAt(2).id)
            assertTrue(root.findViewById<TextView>(R.id.nc_status).text.isNotEmpty())
            val quality = root.findViewById<TextView>(R.id.nc_quality)
            assertTrue(quality.text.contains("Отправка"))
            assertTrue(quality.text.contains("Приём"))
            assertEquals(NotificationQualityText.pair(result, checking), quality.contentDescription.toString())
            for (widthDp in listOf(240, 320, 411)) {
                val density = ctx.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertTrue(root.measuredHeight <= (48 * density + 1).toInt())
            }
        }
    }

    @Test fun idleFirstCheckAndOfflineAlwaysKeepOrder() {
        for (oldCompact in listOf(true, false)) {
            Prefs.setCompactNotification(ctx, oldCompact)
            assertCompactOrder(false, null)
            assertCompactOrder(false, ProbeResult(Level.OFFLINE, -1, -1, 0, "none", 0, 0, "", "", -1))
            assertCompactOrder(false, ProbeResult(Level.WEAK, 2_000_000, 50, 1000, "wifi", 1000, 1000, "host", "", 20_000))
        }
    }

    @Test fun everyLivePhaseKeepsOrderAndShortBigView() {
        Prefs.setCompactNotification(ctx, false)
        for (phase in listOf(Live.CONNECT, Live.DOWN, Live.UP, Live.CALLS, Live.DIAG)) {
            Live.phase = phase
            assertCompactOrder(true, null)
        }
    }

    @Test fun upDownAndTestAlertsKeepOrderAndShortBigView() {
        Prefs.setCompactNotification(ctx, false)
        for (title in listOf("Нормальный интернет появился", "Интернет пропал", "Проверка оповещения")) {
            Notifier::class.java.getDeclaredField("alert").run {
                isAccessible = true
                set(null, Notifier.AlertState(title != "Интернет пропал", Level.GOOD, title, "Подробности", System.currentTimeMillis() + 60_000))
            }
            assertCompactOrder(false, null)
            val n = Notifier.buildStatus(ctx, null, false, Notifier.Info(0, null, 0, false, 0, connection = "Wi-Fi"))
            assertEquals(title, n.contentView.apply(ctx, null).findViewById<TextView>(R.id.nc_status).text.toString())
        }
    }
}
