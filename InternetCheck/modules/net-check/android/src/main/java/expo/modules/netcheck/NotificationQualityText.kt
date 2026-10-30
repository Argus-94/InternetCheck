package expo.modules.netcheck

/** Качество последней проверки, а не текущая активность приложений в счётчиках трафика. */
object NotificationQualityText {
    fun upload(r: ProbeResult?, checking: Boolean = false): String {
        if (r == null) return "Отправка: проверяется…"
        if (r.level == Level.OFFLINE) return "Отправка: нет сети"
        if (r.level == Level.UNKNOWN || r.upBps < 0L) return "Отправка: не измерена"
        val label = when (Level.fromUp(r.upBps)) {
            Level.GOOD -> "Отправка нормальная"
            Level.MEDIUM -> "Отправка средняя"
            Level.WEAK -> "Отправка очень слабая"
            else -> "Отправка почти не идёт"
        }
        return label + if (r.upSkipped || checking) " (прошлая)" else ""
    }

    fun download(r: ProbeResult?, checking: Boolean = false): String {
        if (r == null) return "Приём: проверяется…"
        if (r.level == Level.OFFLINE) return "Приём: нет сети"
        if (r.level == Level.UNKNOWN || r.speedBps < 0L) return "Приём: не измерен"
        val label = when (Level.fromSpeed(r.speedBps)) {
            Level.GOOD -> "Приём нормальный"
            Level.MEDIUM -> "Приём средний"
            Level.WEAK -> "Приём очень слабый"
            else -> "Приём почти не идёт"
        }
        return label + if (checking) " (прошлый)" else ""
    }

    fun pair(r: ProbeResult?, checking: Boolean = false): String =
        upload(r, checking) + "\n" + download(r, checking)
}
