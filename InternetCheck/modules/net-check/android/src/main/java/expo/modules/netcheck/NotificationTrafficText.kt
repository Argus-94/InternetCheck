package expo.modules.netcheck

import java.util.Locale

/** Короткие значения для двух независимых колонок уведомления. Единица не отрывается от числа. */
object NotificationTrafficText {
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond < 0L) return "—"
        val units = arrayOf("Б/с", "КБ/с", "МБ/с", "ГБ/с", "ТБ/с", "ПБ/с", "ЭБ/с")
        var value = bytesPerSecond.toDouble()
        var unit = 0
        while (value >= 1000.0 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        // Не показываем «1000 КБ/с» непосредственно перед переходом к МБ/с.
        if (value >= 999.5 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val number = if (unit == 0 || value >= 100.0) String.format(Locale.US, "%.0f", value)
            else String.format(Locale.US, "%.1f", value)
        return number + "\u00a0" + units[unit]
    }

    fun down(rates: RealtimeTraffic.Rates): String = "↓ " + speed(rates.down)
    fun up(rates: RealtimeTraffic.Rates): String = "↑ " + speed(rates.up)
    fun fallback(rates: RealtimeTraffic.Rates): String = down(rates) + " · " + up(rates)
}
