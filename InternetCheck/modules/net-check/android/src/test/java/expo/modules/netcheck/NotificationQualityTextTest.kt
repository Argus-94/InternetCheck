package expo.modules.netcheck

import org.junit.Assert.*
import org.junit.Test

class NotificationQualityTextTest {
    private fun result(down: Long, up: Long) = ProbeResult(
        Level.combine(Level.fromSpeed(down), up, true), down, 50, 1000,
        "wifi", 0, 1000, "host", "", up
    )

    @Test fun weakUploadDoesNotHideGoodDownload() {
        assertEquals("Отправка очень слабая\nПриём нормальный",
            NotificationQualityText.pair(result(2_000_000, 20_000)))
    }

    @Test fun weakDownloadDoesNotHideGoodUpload() {
        assertEquals("Отправка нормальная\nПриём очень слабый",
            NotificationQualityText.pair(result(20_000, 200_000)))
    }

    @Test fun missingAndZeroUploadAreDifferent() {
        assertEquals("Отправка: не измерена", NotificationQualityText.upload(result(500_000, -1)))
        assertEquals("Отправка почти не идёт", NotificationQualityText.upload(result(500_000, 0)))
    }

    @Test fun unknownAndOfflineDoNotPretendToBeMeasurements() {
        assertEquals("Отправка: проверяется…\nПриём: проверяется…", NotificationQualityText.pair(null))
        val r = result(0, -1).copy(level = Level.OFFLINE)
        assertEquals("Отправка: нет сети\nПриём: нет сети", NotificationQualityText.pair(r))
        assertEquals("Приём: не измерен", NotificationQualityText.download(r.copy(level = Level.UNKNOWN)))
    }

    @Test fun cachedUploadIsExplicitEvenWhenIgnoredByOverallLevel() {
        val r = result(2_000_000, 20_000).copy(level = Level.GOOD, upSkipped = true)
        assertEquals("Отправка очень слабая (прошлая)", NotificationQualityText.upload(r))
        assertEquals("Приём нормальный", NotificationQualityText.download(r))
    }

    @Test fun previousResultsAreMarkedDuringNewCheck() {
        val r = result(500_000, 200_000)
        assertEquals("Отправка нормальная (прошлая)\nПриём нормальный (прошлый)",
            NotificationQualityText.pair(r, true))
        assertFalse(NotificationQualityText.upload(r.copy(upSkipped = true), true).contains("(прошлая) (прошлая)"))
    }

    @Test fun eachDirectionUsesItsOwnThresholds() {
        for ((speed, label) in listOf(9_999L to "почти не идёт", 10_000L to "очень слабая",
            49_999L to "очень слабая", 50_000L to "средняя", 149_999L to "средняя", 150_000L to "нормальная")) {
            assertEquals("Отправка $label", NotificationQualityText.upload(result(500_000, speed)))
        }
        for ((speed, label) in listOf(9_999L to "почти не идёт", 10_000L to "очень слабый",
            99_999L to "очень слабый", 100_000L to "средний", 399_999L to "средний", 400_000L to "нормальный")) {
            assertEquals("Приём $label", NotificationQualityText.download(result(speed, 200_000)))
        }
    }
}
