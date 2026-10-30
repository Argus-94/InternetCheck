package expo.modules.netcheck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Виджет рабочего стола: цвет и скорость текущего состояния интернета. */
class NetWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NetWidget::class.java))
            if (ids.isEmpty()) return
            val v = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, v)
        }

        private fun bg(level: Int): Int = when (level) {
            Level.GOOD -> R.drawable.nc_widget_bg_good
            Level.MEDIUM -> R.drawable.nc_widget_bg_medium
            Level.WEAK -> R.drawable.nc_widget_bg_weak
            Level.NONE -> R.drawable.nc_widget_bg_none
            Level.OFFLINE -> R.drawable.nc_widget_bg_off
            else -> R.drawable.nc_widget_bg_idle
        }

        private fun build(ctx: Context): RemoteViews {
            val r = Prefs.last(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.netcheck_widget)
            val level = r?.level ?: Level.UNKNOWN
            v.setInt(R.id.nc_root, "setBackgroundResource", bg(level))
            if (r == null) {
                v.setTextViewText(R.id.nc_title, "Интернет есть?")
                v.setTextViewText(R.id.nc_speed, "—")
                v.setTextViewText(R.id.nc_sub, "Проверок ещё не было. Нажмите, чтобы открыть.")
            } else {
                v.setTextViewText(R.id.nc_title, Texts.titleFor(r))
                v.setTextViewText(
                    R.id.nc_speed,
                    if (level == Level.OFFLINE) "Нет сети" else Texts.speedPair(r)
                )
                val sb = StringBuilder()
                val since = Prefs.stateSince(ctx)
                if (since > 0L && MonitorService.running) {
                    val stable = Live.stable
                    if (stable == 1) sb.append("В норме уже ").append(Texts.duration(System.currentTimeMillis() - since))
                    else if (stable == 0) sb.append("Нормального нет уже ").append(Texts.duration(System.currentTimeMillis() - since))
                }
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("Проверено в ").append(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.time)))
                if (!MonitorService.running) sb.append(" · слежение выключено")
                v.setTextViewText(R.id.nc_sub, sb.toString())
            }
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
            val pi = PendingIntent.getActivity(
                ctx, 41, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.nc_root, pi)
            return v
        }
    }
}
