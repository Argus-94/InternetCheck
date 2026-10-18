package expo.modules.netcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextsTest {
    private fun res(level: Int, down: Long, up: Long, cause: String = "", transport: String = "wifi") =
        ProbeResult(level, down, 50, 256_000L, transport, 0L, 1000, "host", "", up, cause, "")

    /** Главная ошибка старой версии: скачивание быстрое, а текст говорит «данные не идут». */
    @Test fun fastDownloadWithDeadUploadDoesNotClaimNoData() {
        val r = res(Level.NONE, 3_200_000L, 4_000L, "throttle", "cell")
        val all = listOf(Texts.titleFor(r), Texts.hintFor(r), Texts.summary(r), Texts.causeFor(r)).joinToString(" | ")
        assertFalse(all, all.contains("данные не идут"))
        assertFalse(all, all.contains("данные почти не идут"))
        assertFalse(all, all.contains("фактически нет"))
        assertFalse(all, all.contains("оператор"))
        assertTrue(all, Texts.titleFor(r).contains("Отправка"))
        assertTrue(all, Texts.summary(r).contains("3,2 МБ/с"))
    }

    @Test fun trulyDeadDownloadSaysNoData() {
        val r = res(Level.NONE, 2_000L, -1L, "throttle", "cell")
        assertTrue(Texts.summary(r).contains("данные не идут"))
        assertTrue(Texts.causeFor(r).contains("оператор"))
    }

    @Test fun noOperatorWordOnWifi() {
        val r = res(Level.NONE, 2_000L, -1L, "throttle", "wifi")
        assertFalse(Texts.causeFor(r).contains("оператор"))
    }

    @Test fun mediumOrBetterNeverTalksAboutLimits() {
        val downs = longArrayOf(150_000L, 600_000L, 3_000_000L)
        val ups = longArrayOf(-1L, 60_000L, 400_000L)
        for (d in downs) for (u in ups) {
            val r = res(Level.combine(Level.fromSpeed(d), u, true), d, u)
            if (r.level >= Level.MEDIUM) {
                val all = Texts.titleFor(r) + Texts.hintFor(r) + Texts.summary(r) + Texts.causeFor(r)
                assertFalse("↓$d ↑$u: $all", all.contains("огранич"))
            }
        }
    }

    @Test fun failedUploadIsMarkedNotZero() {
        val r = res(Level.GOOD, 2_000_000L, -1L)
        assertTrue(Texts.upFailed(r))
        assertTrue(Texts.summary(r).contains("не измерена"))
        assertFalse(Texts.summary(r).contains("0 КБ/с"))
    }

    @Test fun skippedUploadIsNotReportedAsFailure() {
        val r = res(Level.GOOD, 2_000_000L, -1L).copy(upSkipped = true)
        assertFalse(Texts.upFailed(r))
        assertFalse(Texts.summary(r).contains("не измерена"))
    }

    @Test fun carriedUploadIsLabelled() {
        val r = res(Level.GOOD, 2_000_000L, 200_000L).copy(upSkipped = true)
        assertTrue(Texts.summary(r).contains("(прошлая)"))
    }

    @Test fun loadNoteOnlyWhenOthersUsedTheChannel() {
        assertEquals("", Texts.loadNote(res(Level.GOOD, 1_000_000L, -1L)))
        val busy = res(Level.GOOD, 1_000_000L, -1L).copy(loadDown = 2_000_000L)
        assertTrue(Texts.loadNote(busy).contains("↓ 2,0 МБ/с"))
    }

    @Test fun speedFormatting() {
        assertEquals("3,2 МБ/с", Texts.speed(3_200_000L))
        assertEquals("150 КБ/с", Texts.speed(150_000L))
        assertEquals("<1 КБ/с", Texts.speed(500L))
        assertEquals("0 КБ/с", Texts.speed(0L))
    }

    @Test fun callsVerdict() {
        fun r(j: Int, l: Int, rtt: Int) = res(Level.GOOD, 1_000_000L, 200_000L).copy(jitterMs = j, lossPct = l, rttMs = rtt)
        assertEquals(3, Texts.callsLevel(r(5, 0, 40)))
        assertEquals(2, Texts.callsLevel(r(5, 3, 40)))
        assertEquals(2, Texts.callsLevel(r(35, 0, 40)))
        assertEquals(1, Texts.callsLevel(r(70, 0, 40)))
        assertEquals(1, Texts.callsLevel(r(5, 8, 40)))
        assertEquals(1, Texts.callsLevel(r(5, 0, 320)))
        assertEquals(0, Texts.callsLevel(res(Level.GOOD, 1_000_000L, -1L)))
        assertEquals("", Texts.callsText(res(Level.GOOD, 1_000_000L, -1L)))
    }
}
