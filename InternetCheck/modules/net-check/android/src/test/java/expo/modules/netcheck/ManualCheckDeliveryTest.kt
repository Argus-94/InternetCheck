package expo.modules.netcheck

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualCheckDeliveryTest {
    @Test fun acceptedCommandFallsBackExactlyOnceIfServiceDiesBeforeDelivery() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("internetcheck_prefs", 0).edit().clear().commit()
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        var fallbackCount = 0
        assertTrue(MonitorService.requestCheck(ctx) { fallbackCount += 1 })
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fallbackCount)
        assertFalse(MonitorService.running)
    }

    @Test fun callerGetsFalseWhenNoServiceCanAcceptCommand() {
        MonitorService.instance = null
        MonitorService.running = false
        var fallbackCount = 0
        assertFalse(MonitorService.requestCheck(RuntimeEnvironment.getApplication()) { fallbackCount += 1 })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, fallbackCount)
    }
}
