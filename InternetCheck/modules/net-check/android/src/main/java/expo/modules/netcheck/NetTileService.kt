package expo.modules.netcheck

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка быстрых настроек: нажатие включает слежение или запускает проверку сразу. */
class NetTileService : TileService() {

    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        val ctx = applicationContext
        try {
            if (!MonitorService.running) {
                if (!MonitorService.canStart(ctx)) {
                    openApp()
                    return
                }
                Prefs.setEnabled(ctx, true)
                if (!MonitorService.start(ctx)) {
                    Prefs.setEnabled(ctx, false)
                    openApp()
                }
            } else {
                MonitorService.requestCheck(ctx)
            }
        } catch (e: Exception) {
            openApp()
        }
        update()
    }

    private fun openApp() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(this, 42, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pi)
            } else {
                // PendingIntent.send works on older Android without invoking the removed Intent overload.
                pi.send()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun update() {
        val t = qsTile ?: return
        val r = Prefs.last(this)
        val on = MonitorService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Интернет есть?"
        t.icon = Icon.createWithResource(this, if (r != null && Level.usable(r.level)) R.drawable.ic_stat_good else R.drawable.ic_stat_wait)
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                !on -> "Выключено · нажмите"
                r == null -> "Проверяю…"
                r.level == Level.OFFLINE -> "Нет сети"
                else -> Texts.speedPair(r)
            }
        }
        t.updateTile()
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    TileService.requestListeningState(ctx, ComponentName(ctx, NetTileService::class.java))
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }
}
