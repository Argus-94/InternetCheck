package expo.modules.netcheck

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceStateRestoreTest {
    private lateinit var controller: ServiceController<MonitorService>
    private lateinit var service: MonitorService
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("internetcheck_prefs", 0)

    private fun field(name: String): Any? = MonitorService::class.java
        .getDeclaredField(name).run { isAccessible = true; get(service) }

    @Before fun setup() {
        prefs.edit().clear().commit()
        controller = Robolectric.buildService(MonitorService::class.java).create()
        service = controller.get()
        // Tests inspect in-memory state; no worker/network operation is needed.
    }

    @After fun teardown() { controller.destroy() }

    @Test fun restoresConfirmedUsableEvenWhenLastProbeIsUnusable() {
        controller.destroy()
        val now = System.currentTimeMillis() - 20_000L
        val unusable = ProbeResult(Level.NONE, 0L, 50, 1L, "wifi", now + 1_000L, 10, "h", "")
        prefs.edit()
            .putString("last", unusable.toJson().toString())
            .putBoolean("stableUsable", true)
            .putLong("stateSince", now)
            .putBoolean("serviceStopped", false)
            .commit()

        controller = Robolectric.buildService(MonitorService::class.java).create()
        service = controller.get()
        assertEquals(true, field("stableUsable"))
        assertEquals(now, field("stateSince"))
    }

    @Test fun migratesStableStateFromLastOnlyWhenConfirmedStateIsAbsent() {
        controller.destroy()
        val now = System.currentTimeMillis() - 10_000L
        val usable = ProbeResult(Level.GOOD, 500_000L, 20, 1L, "wifi", now, 10, "h", "")
        prefs.edit().putString("last", usable.toJson().toString()).putLong("stateSince", now).putBoolean("serviceStopped", false).commit()

        controller = Robolectric.buildService(MonitorService::class.java).create()
        service = controller.get()
        assertEquals(true, field("stableUsable"))
        assertEquals(now, field("stateSince"))
        assertEquals(true, prefs.getBoolean("stableUsable", false))
    }

    @Test fun restartAfterStopStartsWithoutCountingStoppedInterval() {
        controller.destroy()
        prefs.edit().putBoolean("stableUsable", true).putLong("stateSince", 1L).commit()
        controller = Robolectric.buildService(MonitorService::class.java).create()
        service = controller.get()
        assertNull(field("stableUsable"))
        assertEquals(0L, field("stateSince"))
    }
}
