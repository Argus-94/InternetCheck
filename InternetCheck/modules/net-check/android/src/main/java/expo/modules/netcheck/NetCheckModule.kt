package expo.modules.netcheck

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

class NetCheckModule : Module() {
    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

    private var preview: Ringtone? = null
    private val ui = Handler(Looper.getMainLooper())
    private val stopPreview = Runnable { stopPreviewNow() }

    private fun stopPreviewNow() {
        ui.removeCallbacks(stopPreview)
        try {
            preview?.stop()
        } catch (e: Exception) {
            // ignore
        }
        preview = null
    }

    override fun definition() = ModuleDefinition {
        Name("NetCheck")

        // Всё состояние одним JSON — так проще и надёжнее передавать в JS.
        Function("getSnapshot") {
            snapshot()
        }

        Function("startMonitoring") {
            val ctx = context
            try {
                Prefs.setEnabled(ctx, true)
                MonitorService.start(ctx)
                true
            } catch (e: Exception) {
                false
            }
        }

        Function("stopMonitoring") {
            val ctx = context
            Prefs.setEnabled(ctx, false)
            MonitorService.stop(ctx)
            true
        }

        Function("checkNow") {
            val ctx = context.applicationContext
            if (!MonitorService.requestCheck(ctx)) {
                // Слежение выключено — делаем разовую проверку.
                Thread {
                    try {
                        Runner.checkOnce(ctx)
                    } catch (e: Throwable) {
                        // ignore
                    }
                }.start()
            }
            true
        }

        Function("setCheckInterval") { sec: Int ->
            Prefs.setIntervalSec(context, sec)
            true
        }

        Function("setAlertsEnabled") { on: Boolean ->
            Prefs.setAlerts(context, on)
            true
        }

        Function("setAlertsLostEnabled") { on: Boolean ->
            Prefs.setAlertsLost(context, on)
            true
        }

        Function("setAlertSound") { uri: String, title: String ->
            val ctx = context
            Prefs.setSound(ctx, uri, title)
            Notifier.ensureAlertChannel(ctx)
            true
        }

        Function("setVibrate") { on: Boolean ->
            val ctx = context
            Prefs.setVibrate(ctx, on)
            Notifier.ensureAlertChannel(ctx)
            true
        }

        Function("setLoud") { on: Boolean ->
            val ctx = context
            Prefs.setLoud(ctx, on)
            Notifier.ensureAlertChannel(ctx)
            true
        }

        Function("setFastWatch") { on: Boolean ->
            Prefs.setFastWatch(context, on)
            true
        }

        Function("setRepeat") { on: Boolean, sec: Int ->
            Prefs.setRepeat(context, on)
            Prefs.setRepeatSec(context, sec)
            true
        }

        Function("setQuiet") { on: Boolean, from: Int, to: Int ->
            Prefs.setQuiet(context, on, from, to)
            true
        }

        // mode: 0 — средний и выше, 1 — только хороший, 2 — свой порог (КБ/с).
        Function("setTarget") { mode: Int, downKb: Int, upKb: Int, onlyWifi: Boolean ->
            Prefs.setTarget(context, mode, downKb, upKb, onlyWifi)
            true
        }

        // kind: "" — ничего, "app" — приложение (пакет), "url" — ссылка.
        Function("setAction") { kind: String, target: String, label: String ->
            Prefs.setAction(context, kind, target, label)
            true
        }

        Function("getApps") {
            val ctx = context
            val out = JSONArray()
            try {
                val pm = ctx.packageManager
                val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seen = HashSet<String>()
                val rows = ArrayList<Pair<String, String>>()
                for (ri in pm.queryIntentActivities(q, 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == ctx.packageName || !seen.add(pkg)) continue
                    rows.add(Pair(ri.loadLabel(pm).toString(), pkg))
                }
                rows.sortBy { it.first.lowercase() }
                for (r in rows) {
                    out.put(JSONObject().put("label", r.first).put("pkg", r.second))
                }
            } catch (e: Exception) {
                // пустой список
            }
            out.toString()
        }

        // Подтверждение оповещения: останавливает повторы сигнала.
        Function("ackAlert") {
            MonitorService.requestAck(context.applicationContext)
            true
        }

        Function("getChart") { hours: Int ->
            Store.chart(context, hours).toString()
        }

        Function("getStats") { hours: Int ->
            Store.stats(context, hours).toString()
        }

        Function("getNetworks") { hours: Int ->
            Store.networks(context, hours).toString()
        }

        Function("exportReport") { hours: Int ->
            Store.exportText(context, hours)
        }

        Function("clearStats") {
            Store.reset(context)
            true
        }

        Function("clearHistory") {
            Prefs.clearHistory(context)
            true
        }

        Function("testAlert") {
            Notifier.testAlert(context)
            true
        }

        // Список системных звуков: 1 — рингтоны, 2 — уведомления, 4 — будильники.
        Function("getSounds") { type: Int ->
            val out = JSONArray()
            try {
                val rm = RingtoneManager(context)
                rm.setType(type)
                val cur = rm.cursor
                while (cur.moveToNext()) {
                    val o = JSONObject()
                    o.put("title", cur.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                    o.put("uri", rm.getRingtoneUri(cur.position).toString())
                    out.put(o)
                }
            } catch (e: Exception) {
                // пустой список — приложение предложит системные настройки
            }
            out.toString()
        }

        Function("playSound") { uri: String, loud: Boolean ->
            stopPreviewNow()
            try {
                val u = if (uri.isEmpty()) Settings.System.DEFAULT_NOTIFICATION_URI else Uri.parse(uri)
                val r = RingtoneManager.getRingtone(context, u)
                if (r != null) {
                    r.audioAttributes = AudioAttributes.Builder()
                        .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    r.play()
                    preview = r
                    ui.postDelayed(stopPreview, 4000L)
                }
            } catch (e: Exception) {
                // ignore
            }
            true
        }

        Function("stopSound") {
            stopPreviewNow()
            true
        }

        // Тонкая настройка звука в системных настройках канала уведомлений.
        Function("openChannelSettings") {
            val ctx = context
            try {
                val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                i.putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.ensureAlertChannel(ctx))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) {
                try {
                    val j = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    j.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(j)
                } catch (e2: Exception) {
                    // ignore
                }
            }
            true
        }

        Function("setAutostart") { on: Boolean ->
            Prefs.setAutostart(context, on)
            true
        }

        Function("resetTraffic") {
            Prefs.resetTraffic(context)
            true
        }

        Function("requestIgnoreBatteryOptimizations") {
            val ctx = context
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + ctx.packageName)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (e: Exception) {
                try {
                    val j = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(j)
                } catch (e2: Exception) {
                    // ignore
                }
            }
            true
        }
    }

    private fun describe(r: ProbeResult): JSONObject {
        val o = JSONObject()
        o.put("level", r.level)
        o.put("title", Texts.titleFor(r))
        o.put("hint", Texts.hintFor(r))
        o.put("speedBps", r.speedBps)
        o.put("speedText", Texts.speed(r.speedBps))
        o.put("upText", if (r.upBps >= 0L) Texts.speed(r.upBps) else "")
        o.put("upNote", "")
        o.put("loadNote", Texts.loadNote(r))
        o.put("latencyMs", r.latencyMs)
        o.put("transport", r.transport)
        o.put("transportLabel", Texts.transportLabel(r.transport))
        o.put("time", r.time)
        o.put("host", r.host)
        o.put("error", r.error)
        o.put("net", r.net)
        o.put("cause", Texts.causeFor(r))
        return o
    }

    private fun snapshot(): String {
        val ctx = context
        val hist = Prefs.history(ctx)
        val last = Prefs.last(ctx)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

        val o = JSONObject()
        o.put("monitoring", Prefs.enabled(ctx))
        o.put("serviceRunning", MonitorService.running)
        o.put("checking", Runner.isBusy())
        o.put("intervalSec", Prefs.intervalSec(ctx))
        o.put("alerts", Prefs.alerts(ctx))
        o.put("alertsLost", Prefs.alertsLost(ctx))
        o.put("vibrate", Prefs.vibrate(ctx))
        o.put("loud", Prefs.loud(ctx))
        o.put("fastWatch", Prefs.fastWatch(ctx))
        o.put("soundUri", Prefs.soundUri(ctx))
        o.put("soundTitle", Prefs.soundTitle(ctx))
        o.put("stateSince", Prefs.stateSince(ctx))
        o.put("stable", if (Live.stable < 0) JSONObject.NULL else Live.stable == 1)
        val lv = JSONObject()
        lv.put("phase", Live.phase)
        lv.put("downText", if (Live.downBps >= 0L) Texts.speed(Live.downBps) else "")
        lv.put("upText", if (Live.upBps >= 0L) Texts.speed(Live.upBps) else "")
        lv.put("latencyMs", Live.latencyMs)
        lv.put("progress", Live.progress)
        lv.put("quickMode", Live.quickMode)
        lv.put("quickCount", Live.quickCount)
        lv.put("quickAt", Live.quickAt)
        o.put("live", lv)
        o.put("autostart", Prefs.autostart(ctx))
        o.put("repeat", Prefs.repeat(ctx))
        o.put("repeatSec", Prefs.repeatSec(ctx))
        o.put("quietOn", Prefs.quietOn(ctx))
        o.put("quietFrom", Prefs.quietFrom(ctx))
        o.put("quietTo", Prefs.quietTo(ctx))
        o.put("quietNow", Notifier.quietNow(ctx))
        o.put("targetMode", Prefs.targetMode(ctx))
        o.put("targetDownKb", Prefs.targetDownKb(ctx))
        o.put("targetUpKb", Prefs.targetUpKb(ctx))
        o.put("onlyWifi", Prefs.onlyWifi(ctx))
        o.put("actionKind", Prefs.actionKind(ctx))
        o.put("actionTarget", Prefs.actionTarget(ctx))
        o.put("actionLabel", Prefs.actionLabel(ctx))
        o.put("ignoringBattery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        o.put("trafficBytes", Prefs.traffic(ctx))
        o.put("nextCheckAt", if (MonitorService.running) Prefs.nextCheckAt(ctx) else 0L)
        o.put("sdk", Build.VERSION.SDK_INT)
        if (last != null) o.put("last", describe(last)) else o.put("last", JSONObject.NULL)

        val arr = JSONArray()
        for (r in hist) {
            val h = JSONObject()
            h.put("level", r.level)
            h.put("title", Texts.titleFor(r))
            h.put("speedText", Texts.speedPair(r))
            h.put("time", r.time)
            h.put("net", r.net)
            h.put("cause", Texts.causeFor(r))
            arr.put(h)
        }
        o.put("history", arr)
        return o.toString()
    }
}
