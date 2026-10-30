package expo.modules.netcheck

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupPermissionTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        ctx.getSharedPreferences("internetcheck_prefs", 0).edit().clear().commit()
        shadowOf(ctx).grantPermissions("android.permission.POST_NOTIFICATIONS")
        shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(true)
    }
    @Test fun grantedNotificationsAllowStart() {
        assertTrue(MonitorService.canStart(ctx))
    }
    @Test fun deniedRuntimePermissionPreventsBackgroundTraffic() {
        shadowOf(ctx).denyPermissions("android.permission.POST_NOTIFICATIONS")
        assertFalse(MonitorService.start(ctx))
        assertTrue(Prefs.startupIssue(ctx).isNotEmpty())
        assertNull(shadowOf(ctx).nextStartedService)
    }
    @Test fun appLevelNotificationBlockPreventsStart() {
        shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(false)
        assertFalse(MonitorService.start(ctx))
        assertNull(shadowOf(ctx).nextStartedService)
    }
    @Test fun updateDoesNotOverrideDisabledAutostart() {
        Prefs.setEnabled(ctx, true)
        Prefs.setAutostart(ctx, false)
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(ctx).nextStartedService)
    }
    @Test fun bootWithDeniedPermissionRecordsReason() {
        Prefs.setEnabled(ctx, true)
        Prefs.setAutostart(ctx, true)
        shadowOf(ctx).denyPermissions("android.permission.POST_NOTIFICATIONS")
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(Prefs.startupIssue(ctx).isNotEmpty())
        assertNull(shadowOf(ctx).nextStartedService)
    }
}
