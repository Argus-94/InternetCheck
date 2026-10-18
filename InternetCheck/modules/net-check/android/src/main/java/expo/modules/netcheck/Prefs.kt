package expo.modules.netcheck

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Настройки, счётчик трафика, последний результат и история проверок. */
object Prefs {
    private const val NAME = "internetcheck_prefs"
    private const val MAX_HISTORY = 30

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("enabled", v).apply()
    }

    fun intervalSec(ctx: Context): Int = sp(ctx).getInt("intervalSec", 300)
    fun setIntervalSec(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("intervalSec", v.coerceIn(30, 3600)).apply()
    }

    /** Оповещение «нормальный интернет появился». */
    fun alerts(ctx: Context): Boolean = sp(ctx).getBoolean("alerts", true)
    fun setAlerts(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("alerts", v).apply()
    }

    /** Оповещение «интернет пропал». */
    fun alertsLost(ctx: Context): Boolean = sp(ctx).getBoolean("alertsLost", true)
    fun setAlertsLost(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("alertsLost", v).apply()
    }

    /** "" — системный звук уведомления по умолчанию, "silent" — без звука, иначе URI звука. */
    fun soundUri(ctx: Context): String = sp(ctx).getString("soundUri", "") ?: ""
    fun soundTitle(ctx: Context): String = sp(ctx).getString("soundTitle", "") ?: ""
    fun setSound(ctx: Context, uri: String, title: String) {
        sp(ctx).edit().putString("soundUri", uri).putString("soundTitle", title).apply()
    }

    fun vibrate(ctx: Context): Boolean = sp(ctx).getBoolean("vibrate", true)
    fun setVibrate(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("vibrate", v).apply()
    }

    /** Играть звук по громкости будильника. */
    fun loud(ctx: Context): Boolean = sp(ctx).getBoolean("loud", false)
    fun setLoud(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("loud", v).apply()
    }

    /** Быстрое ожидание: частые лёгкие проверки, пока интернета нет. */
    fun fastWatch(ctx: Context): Boolean = sp(ctx).getBoolean("fastWatch", true)
    fun setFastWatch(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("fastWatch", v).apply()
    }

    /** Повторять сигнал, пока пользователь не подтвердит. */
    fun repeat(ctx: Context): Boolean = sp(ctx).getBoolean("repeat", true)
    fun setRepeat(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("repeat", v).apply()
    }

    fun repeatSec(ctx: Context): Int = sp(ctx).getInt("repeatSec", 30)
    fun setRepeatSec(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("repeatSec", v.coerceIn(10, 600)).apply()
    }

    /** Тихие часы: оповещения приходят без звука и вибрации. Время — минуты от полуночи. */
    fun quietOn(ctx: Context): Boolean = sp(ctx).getBoolean("quietOn", false)
    fun quietFrom(ctx: Context): Int = sp(ctx).getInt("quietFrom", 23 * 60)
    fun quietTo(ctx: Context): Int = sp(ctx).getInt("quietTo", 7 * 60)
    fun setQuiet(ctx: Context, on: Boolean, from: Int, to: Int) {
        sp(ctx).edit()
            .putBoolean("quietOn", on)
            .putInt("quietFrom", from.coerceIn(0, 1439))
            .putInt("quietTo", to.coerceIn(0, 1439))
            .apply()
    }

    /** Что считать нормальным: 0 — средний и выше, 1 — только хороший, 2 — свой порог. */
    fun targetMode(ctx: Context): Int = sp(ctx).getInt("targetMode", 0)
    fun targetDownKb(ctx: Context): Int = sp(ctx).getInt("targetDownKb", 400)
    fun targetUpKb(ctx: Context): Int = sp(ctx).getInt("targetUpKb", 100)
    fun onlyWifi(ctx: Context): Boolean = sp(ctx).getBoolean("onlyWifi", false)
    fun setTarget(ctx: Context, mode: Int, downKb: Int, upKb: Int, onlyWifi: Boolean) {
        sp(ctx).edit()
            .putInt("targetMode", mode.coerceIn(0, 2))
            .putInt("targetDownKb", downKb.coerceIn(10, 100_000))
            .putInt("targetUpKb", upKb.coerceIn(0, 100_000))
            .putBoolean("onlyWifi", onlyWifi)
            .apply()
    }

    /** Учитывать ли отправку в итоге. Выключено — итог только по скачиванию, отправка просто показывается. */
    fun upCounts(ctx: Context): Boolean = sp(ctx).getBoolean("upCounts", true)
    fun setUpCounts(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("upCounts", v).apply()
    }

    /** Экономия на мобильной сети: меньше данных на проверку, реже проверки, ежемесячный лимит. */
    fun saveMobile(ctx: Context): Boolean = sp(ctx).getBoolean("saveMobile", true)
    fun mobileLimitMb(ctx: Context): Int = sp(ctx).getInt("mobileLimitMb", 500) // 0 — без лимита
    fun saveBattery(ctx: Context): Boolean = sp(ctx).getBoolean("saveBattery", true)
    fun setSaving(ctx: Context, saveMobile: Boolean, limitMb: Int, saveBattery: Boolean) {
        sp(ctx).edit()
            .putBoolean("saveMobile", saveMobile)
            .putInt("mobileLimitMb", limitMb.coerceIn(0, 100_000))
            .putBoolean("saveBattery", saveBattery)
            .apply()
    }

    private fun monthKey(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 100 + c.get(java.util.Calendar.MONTH) + 1
    }

    /** Сколько проверки потратили по мобильной сети в этом месяце, байт. */
    fun mobileUsed(ctx: Context): Long {
        val s = sp(ctx)
        return if (s.getInt("mobMonth", 0) == monthKey()) s.getLong("mobBytes", 0L) else 0L
    }

    @Synchronized
    fun addMobile(ctx: Context, bytes: Long) {
        val s = sp(ctx)
        val k = monthKey()
        val cur = if (s.getInt("mobMonth", 0) == k) s.getLong("mobBytes", 0L) else 0L
        s.edit().putInt("mobMonth", k).putLong("mobBytes", cur + bytes).apply()
    }

    /** Счётчик полных проверок на мобильной сети: отправку мерим только каждую третью. */
    @Synchronized
    fun nextLightTick(ctx: Context): Int {
        val s = sp(ctx)
        val v = s.getInt("lightTick", 0) + 1
        s.edit().putInt("lightTick", v).apply()
        return v
    }

    /** Действие при появлении интернета: "" — нет, "app" — приложение (пакет), "url" — ссылка. */
    fun actionKind(ctx: Context): String = sp(ctx).getString("actionKind", "") ?: ""
    fun actionTarget(ctx: Context): String = sp(ctx).getString("actionTarget", "") ?: ""
    fun actionLabel(ctx: Context): String = sp(ctx).getString("actionLabel", "") ?: ""
    fun setAction(ctx: Context, kind: String, target: String, label: String) {
        sp(ctx).edit()
            .putString("actionKind", kind)
            .putString("actionTarget", target)
            .putString("actionLabel", label)
            .apply()
    }

    fun autostart(ctx: Context): Boolean = sp(ctx).getBoolean("autostart", true)
    fun setAutostart(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("autostart", v).apply()
    }

    fun nextCheckAt(ctx: Context): Long = sp(ctx).getLong("nextCheckAt", 0L)
    fun setNextCheckAt(ctx: Context, v: Long) {
        sp(ctx).edit().putLong("nextCheckAt", v).apply()
    }

    /** Когда началось текущее состояние (есть/нет нормального интернета). */
    fun stateSince(ctx: Context): Long = sp(ctx).getLong("stateSince", 0L)
    fun setStateSince(ctx: Context, v: Long) {
        sp(ctx).edit().putLong("stateSince", v).apply()
    }

    fun traffic(ctx: Context): Long = sp(ctx).getLong("traffic", 0L)

    @Synchronized
    fun addTraffic(ctx: Context, bytes: Long) {
        val s = sp(ctx)
        s.edit().putLong("traffic", s.getLong("traffic", 0L) + bytes).apply()
    }

    fun resetTraffic(ctx: Context) {
        sp(ctx).edit().putLong("traffic", 0L).putLong("mobBytes", 0L).apply()
    }

    /** Последний результат хранится отдельно: очистка истории его не трогает. */
    @Synchronized
    fun last(ctx: Context): ProbeResult? {
        val raw = sp(ctx).getString("last", null)
        if (raw != null) {
            try {
                return ProbeResult.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                // падаем на историю
            }
        }
        return history(ctx).lastOrNull()
    }

    @Synchronized
    fun history(ctx: Context): List<ProbeResult> {
        val raw = sp(ctx).getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<ProbeResult>()
            for (i in 0 until arr.length()) {
                out.add(ProbeResult.fromJson(arr.getJSONObject(i)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addHistory(ctx: Context, r: ProbeResult) {
        val list = history(ctx).toMutableList()
        list.add(r)
        while (list.size > MAX_HISTORY) list.removeAt(0)
        val arr = JSONArray()
        for (x in list) arr.put(x.toJson())
        sp(ctx).edit()
            .putString("history", arr.toString())
            .putString("last", r.toJson().toString())
            .apply()
    }

    @Synchronized
    fun clearHistory(ctx: Context) {
        // Сначала сохраняем последний результат отдельно (для старых версий данных).
        val last = history(ctx).lastOrNull()
        val e = sp(ctx).edit().remove("history")
        if (last != null && sp(ctx).getString("last", null) == null) {
            e.putString("last", last.toJson().toString())
        }
        e.apply()
    }
}
