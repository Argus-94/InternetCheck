package expo.modules.netcheck

import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

/** Уровни качества интернета. */
object Level {
    const val UNKNOWN = 0
    const val OFFLINE = 1 // телефон не подключён к сети
    const val NONE = 2    // сеть есть, но данные практически не идут
    const val WEAK = 3    // 10–100 КБ/с
    const val MEDIUM = 4  // 100–400 КБ/с
    const val GOOD = 5    // от 400 КБ/с

    // Границы скорости скачивания в байтах в секунду.
    const val NONE_BELOW = 10_000L
    const val WEAK_BELOW = 100_000L
    const val MEDIUM_BELOW = 400_000L

    fun fromSpeed(bps: Long): Int = when {
        bps < NONE_BELOW -> NONE
        bps < WEAK_BELOW -> WEAK
        bps < MEDIUM_BELOW -> MEDIUM
        else -> GOOD
    }

    // Для выгрузки границы ниже: отправлять файлы нужно реже и меньше.
    fun fromUp(bps: Long): Int = when {
        bps < 10_000L -> NONE
        bps < 50_000L -> WEAK
        bps < 150_000L -> MEDIUM
        else -> GOOD
    }

    /** «Нормальный» интернет — средний уровень и выше. */
    fun usable(level: Int): Boolean = level >= MEDIUM

    /** Итог по двум направлениям. Если отправка не важна (настройка) или не измерена — только скачивание. */
    fun combine(downLevel: Int, upBps: Long, upCounts: Boolean): Int =
        if (upBps >= 0L && upCounts) minOf(downLevel, fromUp(upBps)) else downLevel
}

/** Состояние проверки в реальном времени (читается уведомлением и приложением). */
object Live {
    const val IDLE = 0
    const val CONNECT = 1
    const val DOWN = 2
    const val UP = 3
    const val DIAG = 4
    const val CALLS = 5 // дрожание и потери (для звонков и игр)

    @Volatile var phase = IDLE
    @Volatile var downBps = -1L
    @Volatile var upBps = -1L
    @Volatile var latencyMs = -1
    @Volatile var progress = 0       // 0..100 по всей проверке

    /** -1 неизвестно, 0 нормального интернета нет, 1 нормальный. */
    @Volatile var stable = -1

    // Режим ожидания: частые лёгкие запросы, пока интернет не появится.
    @Volatile var quickMode = false
    @Volatile var quickCount = 0
    @Volatile var quickAt = 0L

    fun begin() {
        downBps = -1L
        upBps = -1L
        latencyMs = -1
        progress = 2
        phase = CONNECT
    }

    fun end() {
        phase = IDLE
        progress = 0
    }

    fun onDown(bytes: Long, headersAt: Long, t0: Long, budgetMs: Int, max: Long) {
        val now = SystemClock.elapsedRealtime()
        val el = now - headersAt
        if (el >= 250L) downBps = bytes * 1000L / el
        val frac = maxOf(bytes.toDouble() / max, (now - t0).toDouble() / budgetMs).coerceIn(0.0, 1.0)
        progress = 10 + (frac * 45).toInt()
        phase = DOWN
    }

    fun onUp(written: Long, startAt: Long, total: Int, allowance: Long) {
        val now = SystemClock.elapsedRealtime()
        val el = now - startAt
        if (written > allowance && el >= 300L) upBps = (written - allowance) * 1000L / el
        progress = 62 + (written.toDouble() / total * 38).toInt().coerceAtMost(38)
        phase = UP
    }
}

data class ProbeResult(
    val level: Int,
    val speedBps: Long,
    val latencyMs: Int,
    val bytes: Long,
    val transport: String,
    val time: Long,
    val durationMs: Int,
    val host: String,
    val error: String,
    val upBps: Long = -1L, // скорость выгрузки; -1 — не измерялась
    val cause: String = "",  // причина, если интернета нет (см. Texts.cause)
    val net: String = "",    // название сети: Wi‑Fi, оператор
    val loadDown: Long = 0L, // сколько скачивали другие приложения во время проверки, байт/с (0 — почти ничего)
    val loadUp: Long = 0L,   // сколько отправляли другие приложения во время проверки, байт/с
    val upHost: String = "", // на какой сервер отправляли (для перепроверки другим сервером)
    val upSkipped: Boolean = false, // отправку не мерили (экономия трафика): upBps взят из прошлой проверки
    val upAt: Long = 0L,     // когда выгрузка была реально измерена
    val jitterMs: Int = -1,  // дрожание отклика, мс; -1 — не мерили
    val lossPct: Int = -1,   // потери запросов, %; -1 — не мерили
    val rttMs: Int = -1      // типичный отклик по серии мелких запросов, мс
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("level", level)
        o.put("speedBps", speedBps)
        o.put("latencyMs", latencyMs)
        o.put("bytes", bytes)
        o.put("transport", transport)
        o.put("time", time)
        o.put("durationMs", durationMs)
        o.put("host", host)
        o.put("error", error)
        o.put("upBps", upBps)
        o.put("cause", cause)
        o.put("net", net)
        o.put("loadDown", loadDown)
        o.put("loadUp", loadUp)
        o.put("upHost", upHost)
        o.put("upSkipped", upSkipped)
        o.put("upAt", upAt)
        o.put("jitterMs", jitterMs)
        o.put("lossPct", lossPct)
        o.put("rttMs", rttMs)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ProbeResult = ProbeResult(
            o.optInt("level"),
            o.optLong("speedBps"),
            o.optInt("latencyMs"),
            o.optLong("bytes"),
            o.optString("transport", "none"),
            o.optLong("time"),
            o.optInt("durationMs"),
            o.optString("host", ""),
            o.optString("error", ""),
            o.optLong("upBps", -1L),
            o.optString("cause", ""),
            o.optString("net", ""),
            o.optLong("loadDown", 0L),
            o.optLong("loadUp", 0L),
            o.optString("upHost", ""),
            o.optBoolean("upSkipped", false),
            o.optLong("upAt", 0L),
            o.optInt("jitterMs", -1),
            o.optInt("lossPct", -1),
            o.optInt("rttMs", -1)
        )
    }
}

/** Все тексты для пользователя — в одном месте. Только факты: цифры и пороги. */
object Texts {
    fun title(level: Int): String = when (level) {
        Level.GOOD -> "Интернет нормальный"
        Level.MEDIUM -> "Интернет средний"
        Level.WEAK -> "Интернет очень слабый"
        Level.NONE -> "Интернета фактически нет"
        Level.OFFLINE -> "Нет сети"
        else -> "Проверяю интернет…"
    }

    fun hint(level: Int): String = when (level) {
        Level.GOOD -> "Скачивание от 400 КБ/с, выгрузка от 150 КБ/с."
        Level.MEDIUM -> "Скачивание 100–400 КБ/с или выгрузка 50–150 КБ/с."
        Level.WEAK -> "Скачивание 10–100 КБ/с или выгрузка 10–50 КБ/с."
        Level.NONE -> "Скорость ниже 10 КБ/с — данные почти не идут."
        Level.OFFLINE -> "Нет подключения ни к мобильной сети, ни к Wi‑Fi."
        else -> "Идёт первая проверка."
    }

    fun transportLabel(t: String): String = when (t) {
        "wifi" -> "Wi‑Fi"
        "cell" -> "Мобильная сеть"
        "eth" -> "Ethernet"
        "vpn" -> "VPN"
        "other" -> "Сеть"
        else -> "Нет сети"
    }

    fun speed(bps: Long): String = when {
        bps >= 1_000_000L -> String.format(Locale.US, "%.1f", bps / 1_000_000.0).replace('.', ',') + " МБ/с"
        bps >= 1_000L -> "${bps / 1000} КБ/с"
        bps > 0L -> "<1 КБ/с"
        else -> "0 КБ/с"
    }

    /** Отправку пытались измерить, но не вышло (обрыв, отказ сервера) — это не «0 КБ/с». */
    fun upFailed(r: ProbeResult): Boolean = r.upBps < 0L && r.level >= Level.WEAK && !r.upSkipped

    /** Выгрузка для показа: цифра, «(прошлая)» если взята из прошлой проверки, «не измерена» если не вышло. */
    fun upLabel(r: ProbeResult): String = when {
        r.upBps >= 0L -> "↑ " + speed(r.upBps) + (if (r.upSkipped) " (прошлая)" else "")
        upFailed(r) -> "↑ не измерена"
        else -> ""
    }

    /** Скорость скачивания и выгрузки одной строкой. */
    fun speedPair(r: ProbeResult): String {
        val up = upLabel(r)
        return "↓ ${speed(r.speedBps)}" + (if (up.isEmpty()) "" else " $up")
    }

    /** Пригодность для звонков и игр: 0 нет данных, 1 плохо, 2 терпимо, 3 хорошо. */
    fun callsLevel(r: ProbeResult): Int {
        if (r.lossPct < 0) return 0
        val j = maxOf(r.jitterMs, 0)
        val rtt = maxOf(r.rttMs, 0)
        return when {
            r.lossPct >= 8 || j >= 60 || rtt >= 300 -> 1
            r.lossPct >= 2 || j >= 30 || rtt >= 150 -> 2
            else -> 3
        }
    }

    fun callsText(r: ProbeResult): String {
        val l = callsLevel(r)
        if (l == 0) return ""
        val d = "дрожание ${maxOf(r.jitterMs, 0)} мс, " + (if (r.lossPct == 0) "потерь нет" else "потери ${r.lossPct}%")
        return when (l) {
            3 -> "Для звонков и игр: хорошо ($d)"
            2 -> "Для звонков и игр: терпимо ($d)"
            else -> "Для звонков и игр: плохо ($d)"
        }
    }

    /** Итог определяет выгрузка: скачивание лучше, чем итоговый уровень. */
    fun upOnly(r: ProbeResult): Boolean =
        r.upBps >= 0L && r.level >= Level.NONE && Level.fromSpeed(r.speedBps) > r.level

    /** Заголовок с учётом того, что именно просело: скачивание или отправка. */
    fun titleFor(r: ProbeResult): String = if (upOnly(r)) {
        when (r.level) {
            Level.NONE -> "Отправка почти не идёт"
            Level.WEAK -> "Отправка очень слабая"
            else -> title(r.level)
        }
    } else title(r.level)

    /** Пояснение с цифрами: что именно измерено и почему такой итог. */
    fun hintFor(r: ProbeResult): String = if (upOnly(r)) {
        when (r.level) {
            Level.NONE -> "Скачивание быстрее, а отправка ниже 10 КБ/с."
            Level.WEAK -> "Скачивание быстрее, а отправка 10–50 КБ/с."
            else -> "Скачивание быстрее, а отправка 50–150 КБ/с."
        }
    } else hint(r.level)

    /** Короткая строка для уведомления. */
    fun summary(r: ProbeResult): String = when {
        r.level == Level.OFFLINE -> "Телефон не подключён к сети"
        r.level == Level.NONE && !upOnly(r) ->
            "Сеть есть, данные не идут · ${speed(r.speedBps)} · ${transportLabel(r.transport)}"
        else -> "${speedPair(r)} · отклик ${r.latencyMs} мс · ${transportLabel(r.transport)}"
    }

    /** Предупреждение, что во время проверки канал делили с другими приложениями (и что цифры уже с поправкой). */
    fun loadNote(r: ProbeResult): String {
        if (r.loadDown <= 0L && r.loadUp <= 0L) return ""
        val parts = ArrayList<String>()
        if (r.loadDown > 0L) parts.add("↓ " + speed(r.loadDown))
        if (r.loadUp > 0L) parts.add("↑ " + speed(r.loadUp))
        return "Во время проверки канал использовали другие приложения (" + parts.joinToString(" ") +
            "): скорость указана с их учётом."
    }

    /** Причина с учётом того, какое направление просело. */
    fun causeFor(r: ProbeResult): String =
        if (r.cause == "throttle" && upOnly(r)) "Скачивание работает, а отправка данных почти не идёт."
        else cause(r.cause, r.transport)

    /** Понятная причина, почему интернета нет. Пусто — причина не определялась. */
    fun cause(code: String, transport: String): String = when (code) {
        "offline" -> "Нет подключения к сети."
        "captive" -> "Сеть просит вход через страницу авторизации (портал)."
        "nolink" ->
            if (transport == "cell") "Мобильный интернет не отвечает: нет данных, закончился пакет или блокировка."
            else "Подключение есть, но выхода в интернет нет: не отвечают ни имена, ни адреса."
        "dns" -> "Не работает DNS: имена не находятся, а прямое соединение по адресу работает."
        "throttle" ->
            if (transport == "cell") "Соединение есть, но данные идут очень медленно — возможно, оператор ограничивает скорость."
            else "Соединение есть, но канал пропускает очень мало данных."
        "blocked" -> "Прямое соединение работает, но тестовые серверы недоступны."
        else -> ""
    }

    fun duration(ms: Long): String {
        val s = ms / 1000L
        return when {
            s < 60L -> "$s с"
            s < 3600L -> "${s / 60} мин"
            else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        }
    }
}
