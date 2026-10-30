package expo.modules.netcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Запускает слежение после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        if (!Prefs.enabled(context)) return
        val isBoot = act == Intent.ACTION_BOOT_COMPLETED || act == "android.intent.action.QUICKBOOT_POWERON"
        val isUpdate = act == Intent.ACTION_MY_PACKAGE_REPLACED
        // Package replacement must obey the same explicit autostart policy as boot.
        if ((isBoot || isUpdate) && Prefs.autostart(context)) {
            MonitorService.start(context)
        }
    }
}
