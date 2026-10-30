package expo.modules.netcheck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Кнопка «Принял» в уведомлении (и смахивание одиночного оповещения).
 * Широковещательное намерение доставляется сразу и не запускает службу: подтверждение
 * не должно зависеть от того, работает ли служба и разрешён ли ей фоновый запуск.
 */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MonitorService.ACTION_ACK) return
        MonitorService.requestAck(context.applicationContext)
    }
}
