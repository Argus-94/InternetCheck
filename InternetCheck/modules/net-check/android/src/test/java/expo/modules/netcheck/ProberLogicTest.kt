package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Test

class ProberLogicTest {
    @Test fun completedUploadUsesFullSize() {
        assertEquals(512_000L, Prober.upSpeedOf(true, 512_000L, "", 1000L))
    }

    @Test fun nothingSentIsUnmeasuredNotZero() {
        assertEquals(-1L, Prober.upSpeedOf(false, 0L, "SocketTimeoutException", 3000L))
    }

    @Test fun connectionResetIsUnmeasured() {
        assertEquals(-1L, Prober.upSpeedOf(false, 100_000L, "SocketException", 1500L))
    }

    @Test fun serverRefusalIsUnmeasured() {
        assertEquals(-1L, Prober.upSpeedOf(false, 512_000L, "HTTP 403", 900L))
    }

    @Test fun slowUploadBelowOsBufferIsNotRoundedToZero() {
        assertEquals(25_000L, Prober.upSpeedOf(false, 200_000L, "", 8000L))
    }

    @Test fun slowUploadAboveOsBufferSubtractsBuffer() {
        assertEquals(12_500L, Prober.upSpeedOf(false, 400_000L, "", 8000L))
    }

    @Test fun qualityMedianJitterLoss() {
        val q = Prober.qualityOf(listOf(50L, 52L, 48L, 50L, 51L), 0, 5)
        assertEquals(50, q.rttMs)
        assertEquals(2, q.jitterMs)
        assertEquals(0, q.lossPct)
    }

    @Test fun qualityLossPercent() {
        assertEquals(10, Prober.qualityOf(listOf(50L, 60L), 1, 10).lossPct)
    }

    @Test fun qualityAllLost() {
        val q = Prober.qualityOf(emptyList(), 10, 10)
        assertEquals(100, q.lossPct)
        assertEquals(-1, q.rttMs)
    }

    @Test fun modeSelection() {
        assertEquals(Prober.MODE_FULL, Prober.modeFor("wifi", true, 0L, 500))
        assertEquals(Prober.MODE_FULL, Prober.modeFor("cell", false, 0L, 500))
        assertEquals(Prober.MODE_LIGHT, Prober.modeFor("cell", true, 0L, 500))
        assertEquals(Prober.MODE_MIN, Prober.modeFor("cell", true, 600_000_000L, 500))
        assertEquals(Prober.MODE_LIGHT, Prober.modeFor("cell", true, 9_000_000_000L, 0))
    }

    @Test fun confirmationUsesAnotherServer() {
        val urls = listOf("https://a.example/x", "https://b.example/y", "https://c.example/z")
        assertEquals(urls, Prober.orderUrls(urls, ""))
        assertEquals(
            listOf("https://b.example/y", "https://c.example/z", "https://a.example/x"),
            Prober.orderUrls(urls, "a.example")
        )
        assertEquals("speed.cloudflare.com", Prober.hostOf(Prober.pickUpUrl("")))
        assertEquals("httpbin.org", Prober.hostOf(Prober.pickUpUrl("speed.cloudflare.com")))
    }
}
