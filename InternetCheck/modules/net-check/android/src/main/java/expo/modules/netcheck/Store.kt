package expo.modules.netcheck

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Долгая память для статистики и графика: каждая полная проверка — строка в samples.csv,
 * каждая смена «есть / нет нормального интернета» — строка в events.csv. Хранится 14 дней.
 */
object Store {
    private const val KEEP_MS = 14L * 24L * 3600L * 1000L
    private const val MAX_SAMPLES = 6000
    private var appended = 0

    data class Sample(
        val time: Long, val level: Int, val down: Long, val up: Long,
        val latency: Int, val transport: String, val net: String
    )

    private fun samplesFile(ctx: Context) = File(ctx.applicationContext.filesDir, "samples.csv")
    private fun eventsFile(ctx: Context) = File(ctx.applicationContext.filesDir, "events.csv")

    @Synchronized
    fun addSample(ctx: Context, r: ProbeResult) {
        try {
            val net = r.net.replace(',', ' ').replace('\n', ' ')
            samplesFile(ctx).appendText("${r.time},${r.level},${r.speedBps},${r.upBps},${r.latencyMs},${r.transport},$net\n")
            appended += 1
            if (appended >= 100) {
                appended = 0
                trim(ctx)
            }
        } catch (e: Exception) {
            // статистика не критична
        }
    }

    @Synchronized
    fun addEvent(ctx: Context, time: Long, usable: Boolean) {
        try {
            eventsFile(ctx).appendText("$time,${if (usable) 1 else 0}\n")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun trim(ctx: Context) {
        val minT = System.currentTimeMillis() - KEEP_MS
        val keep = readSamples(ctx, minT)
        val tail = if (keep.size > MAX_SAMPLES) keep.subList(keep.size - MAX_SAMPLES, keep.size) else keep
        val sb = StringBuilder()
        for (s in tail) {
            sb.append("${s.time},${s.level},${s.down},${s.up},${s.latency},${s.transport},${s.net.replace(',', ' ')}\n")
        }
        samplesFile(ctx).writeText(sb.toString())
        val ev = events(ctx)
        val last = ev.lastOrNull { it.first < minT }
        val kept = ev.filter { it.first >= minT }.toMutableList()
        if (last != null) kept.add(0, last)
        val eb = StringBuilder()
        for (e in kept) eb.append("${e.first},${if (e.second) 1 else 0}\n")
        eventsFile(ctx).writeText(eb.toString())
    }

    @Synchronized
    private fun readSamples(ctx: Context, since: Long): List<Sample> {
        val f = samplesFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Sample>()
        try {
            f.forEachLine { line ->
                val p = line.split(',')
                if (p.size >= 6) {
                    val t = p[0].toLongOrNull()
                    if (t != null && t >= since) {
                        out.add(
                            Sample(
                                t, p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: -1L,
                                p[4].toIntOrNull() ?: 0, p[5], if (p.size > 6) p[6] else ""
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    @Synchronized
    private fun events(ctx: Context): List<Pair<Long, Boolean>> {
        val f = eventsFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Pair<Long, Boolean>>()
        try {
            f.forEachLine { line ->
                val p = line.split(',')
                val t = p.getOrNull(0)?.toLongOrNull()
                if (t != null && p.size >= 2) out.add(Pair(t, p[1] == "1"))
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    @Synchronized
    fun reset(ctx: Context) {
        samplesFile(ctx).delete()
        eventsFile(ctx).delete()
    }

    private fun since(hours: Int) = System.currentTimeMillis() - hours * 3600L * 1000L

    /** Итоги за период: доля времени с нормальным интернетом, число и длина перерывов, средние скорости. */
    fun stats(ctx: Context, hours: Int): JSONObject {
        val now = System.currentTimeMillis()
        val from = since(hours)
        val ev = events(ctx)
        var tracked = 0L
        var up = 0L
        var outages = 0
        var longest = 0L
        var cur: Boolean? = ev.lastOrNull { it.first <= from }?.second
        var curStart = from
        for (e in ev.filter { it.first > from }) {
            if (cur == null) {
                cur = e.second
                curStart = e.first
                if (!e.second) outages += 1
                continue
            }
            val dur = e.first - curStart
            tracked += dur
            if (cur) up += dur else longest = maxOf(longest, dur)
            cur = e.second
            curStart = e.first
            if (!e.second) outages += 1
        }
        if (cur != null) {
            val dur = now - curStart
            tracked += dur
            if (cur) up += dur else longest = maxOf(longest, dur)
        }
        val sm = readSamples(ctx, from).filter { it.level > Level.OFFLINE }
        val downs = sm.map { it.down }
        val ups = sm.filter { it.up >= 0L }.map { it.up }
        val o = JSONObject()
        o.put("availability", if (tracked > 0L) up * 100.0 / tracked else JSONObject.NULL)
        o.put("outages", outages)
        o.put("longestMs", longest)
        o.put("avgDown", if (downs.isEmpty()) -1L else downs.sum() / downs.size)
        o.put("avgUp", if (ups.isEmpty()) -1L else ups.sum() / ups.size)
        o.put("checks", readSamples(ctx, from).size)
        return o
    }

    /** Столбики графика: 48 за сутки, 56 за неделю. level 0 — в этот период проверок не было. */
    fun chart(ctx: Context, hours: Int): JSONObject {
        val now = System.currentTimeMillis()
        val from = since(hours)
        val n = if (hours <= 24) 48 else 56
        val step = (now - from) / n
        val sm = readSamples(ctx, from)
        val arr = JSONArray()
        for (i in 0 until n) {
            val a = from + step * i
            val b = a + step
            val inB = sm.filter { it.time >= a && it.time < b }
            val item = JSONObject()
            if (inB.isEmpty()) {
                item.put("l", 0).put("d", 0).put("u", -1)
            } else {
                val ups = inB.filter { it.up >= 0L }.map { it.up }
                item.put("l", inB.minOf { it.level })
                item.put("d", inB.map { it.down }.sum() / inB.size)
                item.put("u", if (ups.isEmpty()) -1L else ups.sum() / ups.size)
            }
            arr.put(item)
        }
        val o = JSONObject()
        o.put("from", from)
        o.put("step", step)
        o.put("items", arr)
        return o
    }

    /** Сравнение сетей: сколько проверок, средняя скорость, доля проверок со средним и выше. */
    fun networks(ctx: Context, hours: Int): JSONArray {
        val sm = readSamples(ctx, since(hours)).filter { it.level > Level.OFFLINE }
        val groups = sm.groupBy { (if (it.net.isEmpty()) Texts.transportLabel(it.transport) else it.net) + "|" + it.transport }
        val rows = groups.entries.map { (k, v) ->
            val ups = v.filter { it.up >= 0L }.map { it.up }
            val o = JSONObject()
            o.put("name", k.substringBeforeLast('|'))
            o.put("transport", k.substringAfterLast('|'))
            o.put("checks", v.size)
            o.put("avgDown", v.map { it.down }.sum() / v.size)
            o.put("avgUp", if (ups.isEmpty()) -1L else ups.sum() / ups.size)
            o.put("goodPct", v.count { Level.usable(it.level) } * 100.0 / v.size)
            o
        }.sortedByDescending { it.getInt("checks") }
        val arr = JSONArray()
        for (r in rows) arr.put(r)
        return arr
    }

    /** Текст для отправки: итоги, перерывы и последние проверки в виде таблицы. */
    fun exportText(ctx: Context, hours: Int): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val st = stats(ctx, hours)
        val sb = StringBuilder()
        sb.append("Интернет есть? — отчёт за ").append(if (hours >= 48) "${hours / 24} дн." else "$hours ч").append('\n')
        sb.append("Сформирован: ").append(fmt.format(Date())).append('\n')
        if (!st.isNull("availability")) {
            sb.append("Нормальный интернет: ").append(String.format(Locale.US, "%.1f", st.getDouble("availability")).replace('.', ',')).append("% времени\n")
        }
        sb.append("Пропаданий: ").append(st.getInt("outages"))
            .append(", самый долгий перерыв: ").append(Texts.duration(st.getLong("longestMs"))).append('\n')
        if (st.getLong("avgDown") >= 0L) sb.append("Средняя скорость: ↓ ").append(Texts.speed(st.getLong("avgDown")))
        if (st.getLong("avgUp") >= 0L) sb.append(" ↑ ").append(Texts.speed(st.getLong("avgUp")))
        sb.append("\nПроверок: ").append(st.getInt("checks")).append("\n\n")

        val from = since(hours)
        val ev = events(ctx).filter { it.first >= from }
        if (ev.isNotEmpty()) {
            sb.append("Смены состояния:\n")
            for (e in ev.takeLast(60)) {
                sb.append(fmt.format(Date(e.first))).append(" — ")
                    .append(if (e.second) "нормальный интернет появился" else "нормального интернета нет").append('\n')
            }
            sb.append('\n')
        }
        sb.append("Время;Уровень;Скачивание КБ/с;Выгрузка КБ/с;Отклик мс;Сеть\n")
        for (s in readSamples(ctx, from).takeLast(400)) {
            sb.append(fmt.format(Date(s.time))).append(';')
                .append(Texts.title(s.level)).append(';')
                .append(s.down / 1000).append(';')
                .append(if (s.up >= 0L) (s.up / 1000).toString() else "").append(';')
                .append(s.latency).append(';')
                .append(if (s.net.isEmpty()) Texts.transportLabel(s.transport) else s.net).append('\n')
        }
        return sb.toString()
    }
}
