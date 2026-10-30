package expo.modules.netcheck

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickTrafficBudgetTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        ctx.getSharedPreferences("internetcheck_prefs", 0).edit().clear().commit()
    }
    @Test fun everyCellularAttemptContributesToMonthlyBudget() {
        val bytes = Quick.estimatedTraffic(3)
        repeat(10) { Quick.accountTraffic(ctx, "cell", bytes) }
        assertEquals(45_000L, Prefs.mobileUsed(ctx))
        assertEquals(45_000L, Prefs.traffic(ctx))
    }
    @Test fun wifiDoesNotConsumeCellularBudget() {
        Quick.accountTraffic(ctx, "wifi", Quick.estimatedTraffic(3))
        assertEquals(0L, Prefs.mobileUsed(ctx))
        assertEquals(4_500L, Prefs.traffic(ctx))
    }
    @Test fun switchingTransportRetainsPreviousCellularConsumption() {
        Quick.accountTraffic(ctx, "cell", 6_000L)
        Quick.accountTraffic(ctx, "wifi", 4_500L)
        Quick.accountTraffic(ctx, "cell", 5_000L)
        assertEquals(11_000L, Prefs.mobileUsed(ctx))
        assertEquals(15_500L, Prefs.traffic(ctx))
    }
    @Test fun invalidNegativeEstimateCannotReduceBudget() {
        Quick.accountTraffic(ctx, "cell", 4_500L)
        Quick.accountTraffic(ctx, "cell", -1L)
        assertEquals(4_500L, Prefs.mobileUsed(ctx))
    }
}
