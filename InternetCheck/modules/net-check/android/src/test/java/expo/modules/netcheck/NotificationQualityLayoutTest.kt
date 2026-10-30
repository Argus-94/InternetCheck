package expo.modules.netcheck

import android.app.Notification
import android.content.Context
import android.view.View
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
class NotificationQualityLayoutTest {
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Notifier.clearAlert()
        Live.phase = Live.IDLE
        Notifier.ensureChannels(ctx)
    }

    private fun status(checking: Boolean = false): Notification = Notifier.buildStatus(ctx,
        ProbeResult(Level.WEAK, 2_000_000, 50, 1000, "wifi", 1000, 1000, "host", "", 20_000),
        checking, Notifier.Info(0, null, 0, false, 0,
            trafficRates = RealtimeTraffic.Rates(0, 0), connection = "Wi-Fi"))

    @Test fun bothCompactFormsContainIndependentQualityNotIdleTraffic() {
        val n = status()
        for (remote in arrayOf(n.contentView, n.bigContentView)) {
            val root = remote.apply(ctx, null)
            assertEquals("Отправка очень слабая · Приём нормальный",
                root.findViewById<TextView>(R.id.nc_quality).text.toString())
            assertTrue(root.findViewById<TextView>(R.id.nc_quality).maxLines <= 1)
            root.measure(View.MeasureSpec.makeMeasureSpec((320 * ctx.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue("Compact layout must fit Android 12's 48dp limit",
                root.measuredHeight <= (48 * ctx.resources.displayMetrics.density + 1).toInt())
        }
        assertTrue(n.extras.getString(Notification.EXTRA_TEXT)!!.contains("Приём нормальный"))
    }

    @Test fun oldDetailedPreferenceKeepsStatusAndBothQualities() {
        Prefs.setCompactNotification(ctx, false)
        val root = status().bigContentView.apply(ctx, null)
        assertEquals("Отправка очень слабая · Приём нормальный",
            root.findViewById<TextView>(R.id.nc_quality).text.toString())
        assertEquals("Отправка очень слабая", root.findViewById<TextView>(R.id.nc_status).text.toString())
    }

    @Test fun runningCheckDoesNotPassPreviousResultsOffAsNew() {
        Live.phase = Live.DOWN
        val n = status(true)
        val root = n.contentView.apply(ctx, null)
        assertEquals("Отправка очень слабая (прошлая) · Приём нормальный (прошлый)",
            root.findViewById<TextView>(R.id.nc_quality).text.toString())
        assertTrue(n.extras.getString(Notification.EXTRA_SUB_TEXT)!!.contains("скачивание"))
    }
}
