package expo.modules.netcheck

import org.json.JSONArray
import org.json.JSONObject

/**
 * Самопроверка логики: пороги, итоговый уровень, тексты уведомлений, расчёт скорости отправки,
 * дрожание и потери, выбор режима и сервера. Запускается кнопкой в настройках и работает без сети.
 * Главное — ловит противоречия вида «скачивание нормальное, а текст говорит, что данные не идут».
 */
object SelfTest {
    private class Checker {
        var total = 0
        val failures = ArrayList<String>()

        fun eq(name: String, actual: Any?, expected: Any?) {
            total += 1
            if (actual != expected) failures.add("$name: ожидалось $expected, получено $actual")
        }

        fun ok(name: String, cond: Boolean) {
            total += 1
            if (!cond) failures.add(name)
        }
    }

    private fun result(level: Int, down: Long, up: Long, cause: String = "", transport: String = "wifi") =
        ProbeResult(level, down, 50, 256_000L, transport, 0L, 1000, "host", "", up, cause, "")

    /** Итог прогона. Не зависит от Android, поэтому его же проверяет обычный JUnit-тест. */
    class Report(val total: Int, val failures: List<String>)

    fun check(): Report {
        val t = Checker()
        try {
            levels(t)
            combine(t)
            textGrid(t)
            uploadSpeed(t)
            quality(t)
            calls(t)
            modes(t)
            servers(t)
            foreignTraffic(t)
            formats(t)
        } catch (e: Throwable) {
            t.failures.add("Самопроверка упала: " + e.javaClass.simpleName + " " + (e.message ?: ""))
        }
        return Report(t.total, t.failures)
    }

    /** Тот же прогон в виде JSON для экрана настроек. */
    fun run(): String {
        val rep = check()
        val o = JSONObject()
        o.put("total", rep.total)
        o.put("failed", rep.failures.size)
        val arr = JSONArray()
        for (f in rep.failures) arr.put(f)
        o.put("failures", arr)
        return o.toString()
    }

    private fun levels(t: Checker) {
        t.eq("скачивание 9 999 → нет", Level.fromSpeed(9_999L), Level.NONE)
        t.eq("скачивание 10 000 → слабый", Level.fromSpeed(10_000L), Level.WEAK)
        t.eq("скачивание 99 999 → слабый", Level.fromSpeed(99_999L), Level.WEAK)
        t.eq("скачивание 100 000 → средний", Level.fromSpeed(100_000L), Level.MEDIUM)
        t.eq("скачивание 399 999 → средний", Level.fromSpeed(399_999L), Level.MEDIUM)
        t.eq("скачивание 400 000 → хороший", Level.fromSpeed(400_000L), Level.GOOD)
        t.eq("отправка 9 999 → нет", Level.fromUp(9_999L), Level.NONE)
        t.eq("отправка 10 000 → слабый", Level.fromUp(10_000L), Level.WEAK)
        t.eq("отправка 50 000 → средний", Level.fromUp(50_000L), Level.MEDIUM)
        t.eq("отправка 150 000 → хороший", Level.fromUp(150_000L), Level.GOOD)
    }

    private fun combine(t: Checker) {
        t.eq("итог: плохая отправка тянет вниз", Level.combine(Level.GOOD, 5_000L, true), Level.NONE)
        t.eq("итог: отправка не важна", Level.combine(Level.GOOD, 5_000L, false), Level.GOOD)
        t.eq("итог: отправка не измерена", Level.combine(Level.GOOD, -1L, true), Level.GOOD)
        t.eq("итог: средний", Level.combine(Level.MEDIUM, 900_000L, true), Level.MEDIUM)
        t.eq("итог: слабое скачивание", Level.combine(Level.WEAK, 900_000L, true), Level.WEAK)
    }

    /** Перебираем сочетания скорости и проверяем, что тексты не противоречат цифрам. */
    private fun textGrid(t: Checker) {
        val downs = longArrayOf(0L, 5_000L, 20_000L, 150_000L, 600_000L, 3_000_000L)
        val ups = longArrayOf(-1L, 0L, 5_000L, 30_000L, 100_000L, 400_000L)
        for (d in downs) {
            for (u in ups) {
                val downLevel = Level.fromSpeed(d)
                // Отправку мерим только если данные идут.
                val up = if (downLevel >= Level.WEAK) u else -1L
                val level = Level.combine(downLevel, up, true)
                val cause = if (level <= Level.NONE) "throttle" else ""
                val r = result(level, d, up, cause)
                val label = "↓$d ↑$up"
                val all = Texts.titleFor(r) + " | " + Texts.hintFor(r) + " | " + Texts.summary(r) + " | " + Texts.causeFor(r)
                t.ok("$label: пустой заголовок", Texts.titleFor(r).isNotEmpty())
                t.ok("$label: пустая строка итога", Texts.summary(r).isNotEmpty())
                if (downLevel > Level.NONE) {
                    // Скачивание работает — текст не должен говорить, что данные не идут или интернета нет.
                    t.ok("$label: скачивание идёт, а текст говорит «не идут»: $all",
                        !all.contains("данные не идут") && !all.contains("данные почти не идут") && !all.contains("фактически нет"))
                }
                if (level >= Level.MEDIUM) {
                    t.ok("$label: уровень не ниже среднего, а текст про ограничение: $all", !all.contains("огранич"))
                }
                if (Texts.upFailed(r)) {
                    t.ok("$label: отправка не измерена, а в строке этого нет", Texts.summary(r).contains("не измерена"))
                }
                t.ok("$label: слово «оператор» для не мобильной сети: $all", !all.contains("оператор"))
            }
        }
        // Отправка не измерилась (обрыв): это не «0 КБ/с».
        val failed = result(Level.GOOD, 2_000_000L, -1L)
        t.ok("обрыв отправки: должна быть пометка «не измерена»", Texts.upFailed(failed) && Texts.summary(failed).contains("не измерена"))
        // Отправку не мерили из экономии: пометка «не измерена» не нужна.
        val skipped = failed.copy(upSkipped = true)
        t.ok("экономия: пометки «не измерена» быть не должно", !Texts.upFailed(skipped) && !Texts.summary(skipped).contains("не измерена"))
        val carried = skipped.copy(upBps = 200_000L)
        t.ok("перенесённая отправка помечена «(прошлая)»", Texts.summary(carried).contains("(прошлая)"))
        // Чужой трафик.
        t.eq("чужого трафика нет — пометки нет", Texts.loadNote(result(Level.GOOD, 1_000_000L, -1L)), "")
        t.ok("чужой трафик — пометка есть", Texts.loadNote(result(Level.GOOD, 1_000_000L, -1L).copy(loadDown = 2_000_000L)).contains("↓ 2,0 МБ/с"))
    }

    private fun uploadSpeed(t: Checker) {
        t.eq("отправка завершилась: 512 КБ за 1 с", Prober.upSpeedOf(true, 512_000L, "", 1000L), 512_000L)
        t.eq("ни байта не ушло — не измерено", Prober.upSpeedOf(false, 0L, "SocketTimeoutException", 3000L), -1L)
        t.eq("обрыв соединения — не измерено", Prober.upSpeedOf(false, 100_000L, "SocketException", 1500L), -1L)
        t.eq("отказ сервера — не измерено", Prober.upSpeedOf(false, 512_000L, "HTTP 403", 900L), -1L)
        t.eq("медленно, меньше буфера ОС — не ноль", Prober.upSpeedOf(false, 200_000L, "", 8000L), 25_000L)
        t.eq("медленно, больше буфера ОС", Prober.upSpeedOf(false, 400_000L, "", 8000L), 12_500L)
    }

    private fun quality(t: Checker) {
        val q = Prober.qualityOf(listOf(50L, 52L, 48L, 50L, 51L), 0, 5)
        t.eq("отклик (медиана)", q.rttMs, 50)
        t.eq("дрожание", q.jitterMs, 2)
        t.eq("потерь нет", q.lossPct, 0)
        t.eq("потери 10%", Prober.qualityOf(listOf(50L, 60L), 1, 10).lossPct, 10)
        val all = Prober.qualityOf(emptyList(), 10, 10)
        t.eq("все потеряны: потери", all.lossPct, 100)
        t.eq("все потеряны: отклика нет", all.rttMs, -1)
    }

    private fun calls(t: Checker) {
        fun r(j: Int, l: Int, rtt: Int) = result(Level.GOOD, 1_000_000L, 200_000L).copy(jitterMs = j, lossPct = l, rttMs = rtt)
        t.eq("звонки: хорошо", Texts.callsLevel(r(5, 0, 40)), 3)
        t.eq("звонки: потери 3% — терпимо", Texts.callsLevel(r(5, 3, 40)), 2)
        t.eq("звонки: дрожание 70 — плохо", Texts.callsLevel(r(70, 0, 40)), 1)
        t.eq("звонки: отклик 320 — плохо", Texts.callsLevel(r(5, 0, 320)), 1)
        t.eq("звонки: не мерили", Texts.callsLevel(result(Level.GOOD, 1_000_000L, -1L)), 0)
        t.eq("звонки: нет текста, если не мерили", Texts.callsText(result(Level.GOOD, 1_000_000L, -1L)), "")
        t.ok("звонки: текст «плохо»", Texts.callsText(r(70, 0, 40)).contains("плохо"))
    }

    private fun modes(t: Checker) {
        t.eq("Wi‑Fi — полная проверка", Prober.modeFor("wifi", true, 0L, 500), Prober.MODE_FULL)
        t.eq("мобильная без экономии — полная", Prober.modeFor("cell", false, 0L, 500), Prober.MODE_FULL)
        t.eq("мобильная с экономией — облегчённая", Prober.modeFor("cell", true, 0L, 500), Prober.MODE_LIGHT)
        t.eq("лимит исчерпан — минимальная", Prober.modeFor("cell", true, 600_000_000L, 500), Prober.MODE_MIN)
        t.eq("без лимита — облегчённая", Prober.modeFor("cell", true, 9_000_000_000L, 0), Prober.MODE_LIGHT)
    }

    private fun servers(t: Checker) {
        val urls = listOf("https://a.example/x", "https://b.example/y", "https://c.example/z")
        t.eq("порядок без исключения", Prober.orderUrls(urls, ""), urls)
        t.eq("прошлый сервер уходит в конец",
            Prober.orderUrls(urls, "a.example"), listOf("https://b.example/y", "https://c.example/z", "https://a.example/x"))
        t.eq("отправка: обычный адрес", Prober.hostOf(Prober.pickUpUrl("")), "speed.cloudflare.com")
        t.eq("отправка: перепроверка через другой", Prober.hostOf(Prober.pickUpUrl("speed.cloudflare.com")), "httpbin.org")
    }

    private fun foreignTraffic(t: Checker) {
        val a = Traffic.Snap(0L, 0L, 0L, 0L, 0L)
        val busy = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 1000L)
        t.eq("чужой трафик: 2 МБ/с", Traffic.foreign(a, busy, false), 2_000_000L)
        val quiet = Traffic.Snap(500_000L, 0L, 505_000L, 0L, 1000L)
        t.eq("чужой трафик: шум системы не считается", Traffic.foreign(a, quiet, false), 0L)
        t.eq("чужой трафик: нет замера", Traffic.foreign(null, busy, false), 0L)
        val tooShort = Traffic.Snap(500_000L, 0L, 2_500_000L, 0L, 100L)
        t.eq("чужой трафик: слишком короткое окно", Traffic.foreign(a, tooShort, false), 0L)
        val reset = Traffic.Snap(0L, 0L, 0L, 0L, 1000L)
        t.eq("чужой трафик: счётчики сбросились", Traffic.foreign(busy, reset, false), 0L)
    }

    private fun formats(t: Checker) {
        t.eq("формат: МБ/с", Texts.speed(3_200_000L), "3,2 МБ/с")
        t.eq("формат: КБ/с", Texts.speed(150_000L), "150 КБ/с")
        t.eq("формат: меньше 1 КБ/с", Texts.speed(500L), "<1 КБ/с")
    }
}
