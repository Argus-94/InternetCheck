package expo.modules.netcheck

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleRegressionTest {
    @Test fun quickAttemptsAreNotLostAcrossWorkers() {
        Live.quickCount = 0
        Live.quickAt = 0L
        val n = 64
        val done = CountDownLatch(n)
        repeat(n) {
            thread {
                Live.recordQuickAttempt(it.toLong())
                done.countDown()
            }
        }
        done.await()
        assertEquals(n, Live.quickCount)
    }
}
