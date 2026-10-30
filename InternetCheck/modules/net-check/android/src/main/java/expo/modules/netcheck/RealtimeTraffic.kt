package expo.modules.netcheck

import java.util.Locale

/** Пассивный счётчик всего устройства: не запускает запросов и не зависит от speed test. */
class RealtimeTraffic {
    data class Sample(val rx: Long, val tx: Long, val at: Long, val source: String)
    data class Rates(val down: Long = -1L, val up: Long = -1L)

    private var previous: Sample? = null

    fun update(rx: Long, tx: Long, elapsedMs: Long, source: String = DEFAULT_SOURCE): Rates {
        val current = Sample(rx, tx, elapsedMs, source)
        val old = previous
        previous = current
        if (old == null) return Rates()
        if (old.source != current.source) return Rates()
        val dt = current.at - old.at
        // После сна не выдаём среднее за много минут за «скорость сейчас».
        if (dt <= 0L || dt > 5_000L) return Rates()
        return Rates(rate(old.rx, rx, dt), rate(old.tx, tx, dt))
    }

    companion object {
        private const val DEFAULT_SOURCE = "default"

        private fun rate(before: Long, after: Long, ms: Long): Long {
            if (before < 0L || after < before) return -1L
            return ((after - before).toDouble() * 1000.0 / ms).toLong()
        }

        fun speed(bytesPerSecond: Long): String = when {
            bytesPerSecond < 0L -> "—"
            bytesPerSecond < 1000L -> "$bytesPerSecond Б/с"
            bytesPerSecond < 1_000_000L -> String.format(Locale.US, "%.1f КБ/с", bytesPerSecond / 1000.0)
            else -> String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / 1_000_000.0)
        }

        fun line(connection: String, rates: Rates): String =
            "$connection · ↓ ${speed(rates.down)} · ↑ ${speed(rates.up)}"
    }
}
