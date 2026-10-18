package expo.modules.netcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    const val CH_STATUS = "status"
    const val CH_LOST = "lost"
    const val CH_QUIET = "quiet"
    private const val CH_ALERT_PREFIX = "alert_"
    private const val CH_OLD_ALERTS = "alerts" // канал из версии 1.0.1
    const val ID_STATUS = 4101
    private val VIBRATION = longArrayOf(0, 300, 150, 300, 150, 600)
    private const val ALERT_SHOW_MS = 10L * 60L * 1000L
    private const val TEST_SHOW_MS = 15_000L

    /** Оповещение живёт внутри единственного уведомления службы: оно показывается поверх статуса. */
    class AlertState(val up: Boolean, val level: Int, val title: String, val text: String, val until: Long)

    @Volatile
    private var alert: AlertState? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun activeAlert(): AlertState? {
        val a = alert ?: return null
        if (System.currentTimeMillis() >= a.until) {
            alert = null
            return null
        }
        return a
    }

    /** Убирает оповещение из уведомления (статус остаётся). */
    fun clearAlert() {
        alert = null
    }

    /** Данные о состоянии службы для уведомления. */
    data class Info(
        val stateSince: Long,
        val stable: Boolean?,
        val nextAt: Long,
        val quick: Boolean,
        val quickCount: Int
    )

    // ---------- каналы ----------

    /** Канал оповещения «интернет появился» зависит от звука и вибрации: у каналов их нельзя менять на лету. */
    fun alertChannelId(ctx: Context): String {
        val key = Prefs.soundUri(ctx) + "|" + Prefs.loud(ctx) + "|" + Prefs.vibrate(ctx)
        return CH_ALERT_PREFIX + Integer.toHexString(key.hashCode())
    }

    private fun soundAttrs(loud: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun soundUriOrNull(ctx: Context): Uri? {
        val s = Prefs.soundUri(ctx)
        return when (s) {
            "silent" -> null
            "" -> Settings.System.DEFAULT_NOTIFICATION_URI
            else -> try {
                Uri.parse(s)
            } catch (e: Exception) {
                Settings.System.DEFAULT_NOTIFICATION_URI
            }
        }
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val st = NotificationChannel(CH_STATUS, "Состояние интернета", NotificationManager.IMPORTANCE_LOW)
        st.description = "Постоянное уведомление со статусом и проверкой в реальном времени"
        st.setShowBadge(false)
        st.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(st)

        val lost = NotificationChannel(CH_LOST, "Интернет пропал", NotificationManager.IMPORTANCE_DEFAULT)
        lost.description = "Сообщает, когда нормальный интернет пропал"
        lost.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(lost)

        val quiet = NotificationChannel(CH_QUIET, "Оповещения в тихие часы", NotificationManager.IMPORTANCE_LOW)
        quiet.description = "Те же оповещения, но без звука и вибрации — в часы, выбранные в настройках"
        quiet.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(quiet)

        try {
            nm.deleteNotificationChannel(CH_OLD_ALERTS)
        } catch (e: Exception) {
            // ignore
        }
        ensureAlertChannel(ctx)
    }

    /** Создаёт канал со звуком, выбранным пользователем, и удаляет старые варианты. */
    fun ensureAlertChannel(ctx: Context): String {
        val id = alertChannelId(ctx)
        if (Build.VERSION.SDK_INT < 26) return id
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "Интернет появился", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Сигнал, когда нормальный интернет появился"
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val uri = soundUriOrNull(ctx)
            if (uri == null) ch.setSound(null, null) else ch.setSound(uri, soundAttrs(Prefs.loud(ctx)))
            ch.enableVibration(Prefs.vibrate(ctx))
            if (Prefs.vibrate(ctx)) ch.vibrationPattern = VIBRATION
            nm.createNotificationChannel(ch)
        }
        try {
            for (c in nm.notificationChannels) {
                if (c.id.startsWith(CH_ALERT_PREFIX) && c.id != id) nm.deleteNotificationChannel(c.id)
            }
        } catch (e: Exception) {
            // ignore
        }
        return id
    }

    /** Сейчас тихие часы? */
    fun quietNow(ctx: Context): Boolean {
        if (!Prefs.quietOn(ctx)) return false
        val c = java.util.Calendar.getInstance()
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val from = Prefs.quietFrom(ctx)
        val to = Prefs.quietTo(ctx)
        return when {
            from == to -> false
            from < to -> m in from until to
            else -> m >= from || m < to // например, 23:00–07:00
        }
    }

    // ---------- сборка ----------

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else Notification.Builder(ctx)

    private fun iconFor(level: Int): Int = when (level) {
        Level.GOOD, Level.MEDIUM -> R.drawable.ic_stat_good
        Level.WEAK -> R.drawable.ic_stat_warn
        Level.NONE -> R.drawable.ic_stat_none
        Level.OFFLINE -> R.drawable.ic_stat_off
        else -> R.drawable.ic_stat_wait
    }

    private fun colorFor(level: Int): Int = when (level) {
        Level.GOOD -> 0xFF1E7B34.toInt()
        Level.MEDIUM -> 0xFF4D7C0F.toInt()
        Level.WEAK -> 0xFFB45309.toInt()
        Level.NONE -> 0xFFB91C1C.toInt()
        Level.OFFLINE -> 0xFF4B5563.toInt()
        else -> 0xFF334155.toInt()
    }

    private fun openApp(ctx: Context): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        return PendingIntent.getActivity(
            ctx, 10, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun action(ctx: Context, label: String, act: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            ctx, req,
            Intent(ctx, MonitorService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_wait), label, pi).build()
    }

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun clockSec(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

    /** Строка с текущими цифрами проверки: «↓ 340 КБ/с · ↑ 120 КБ/с · отклик 80 мс». */
    private fun liveLine(): String {
        val parts = ArrayList<String>()
        if (Live.downBps >= 0L) parts.add("↓ " + Texts.speed(Live.downBps))
        if (Live.upBps >= 0L) parts.add("↑ " + Texts.speed(Live.upBps))
        if (Live.latencyMs >= 0) parts.add("отклик ${Live.latencyMs} мс")
        return if (parts.isEmpty()) "Измеряю скорость…" else parts.joinToString(" · ")
    }

    private fun liveTitle(): String = when (Live.phase) {
        Live.CONNECT -> "Проверка · подключение…"
        Live.DOWN -> "Проверка · скачивание" + (if (Live.downBps >= 0L) " ↓ " + Texts.speed(Live.downBps) else "…")
        Live.UP -> "Проверка · отправка" + (if (Live.upBps >= 0L) " ↑ " + Texts.speed(Live.upBps) else "…")
        Live.CALLS -> "Проверка · звонки и игры…"
        Live.DIAG -> "Проверка · выясняю причину…"
        else -> "Проверка…"
    }

    /**
     * Единственное уведомление приложения. Обычно это статус и ход проверки; когда есть оповещение
     * («появился», «пропал», пробное), оно показывается в этом же уведомлении, а не отдельным.
     * channel/fresh нужны только в момент самого оповещения: звук играет один раз, дальше — тихие обновления.
     */
    fun buildStatus(
        ctx: Context, r: ProbeResult?, checking: Boolean, info: Info,
        channel: String = CH_STATUS, fresh: Boolean = false
    ): Notification {
        val live = checking && Live.phase != Live.IDLE
        val level = r?.level ?: Level.UNKNOWN
        val b = builder(ctx, channel)
            .setOngoing(true)
            .setOnlyAlertOnce(!fresh)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))

        if (live) {
            b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
            b.addAction(action(ctx, "Выключить", MonitorService.ACTION_STOP, 12))
            val text = liveLine()
            val prev = if (r != null) "\nПредыдущая проверка в ${clock(r.time)}: ${Texts.titleFor(r)} · ${Texts.summary(r)}" else ""
            b.setSmallIcon(R.drawable.ic_stat_wait)
                .setContentTitle(liveTitle())
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text + prev))
                .setColor(colorFor(Level.UNKNOWN))
                .setProgress(100, Live.progress.coerceIn(0, 100), false)
                .setShowWhen(false)
                .setSubText("проверяю…")
        } else {
            val al = activeAlert()
            val title = if (r == null) Texts.title(level) else Texts.titleFor(r)
            val text = if (r == null) "Идёт первая проверка…" else Texts.summary(r)
            val usableNow = r != null && Target.usable(ctx, r)
            // Первая проверка хуже нормы: падение ещё не подтверждено второй проверкой.
            val pending = r != null && info.stable == true && !usableNow
            val sb = StringBuilder(text)
            if (r != null) {
                sb.append("\n").append(Texts.hintFor(r))
                val cause = Texts.causeFor(r)
                if (cause.isNotEmpty()) sb.append("\n").append(cause)
                val load = Texts.loadNote(r)
                if (load.isNotEmpty()) sb.append("\n").append(load)
                val calls = Texts.callsText(r)
                if (calls.isNotEmpty()) sb.append("\n").append(calls)
                // Результат есть, но «нормальным» не считается — объясняем почему, чтобы не было противоречия.
                if (!usableNow && r.level > Level.NONE) {
                    sb.append("\n").append(
                        if (Prefs.onlyWifi(ctx) && r.transport != "wifi" && r.transport != "eth")
                            "Для оповещений выбрано «только Wi‑Fi», а сейчас другая сеть."
                        else "Ниже выбранного вами порога «нормального» интернета."
                    )
                }
                if (r.net.isNotEmpty()) sb.append("\nСеть: ").append(r.net)
                sb.append("\nПроверено в ").append(clock(r.time))
                if (info.nextAt > System.currentTimeMillis()) sb.append(" · следующая в ").append(clock(info.nextAt))
            }
            if (info.quick) {
                sb.append("\nЖду интернет: проверка каждые 5 с, попыток ").append(info.quickCount)
                if (Live.quickAt > 0L) sb.append(" · последняя в ").append(clockSec(Live.quickAt))
            }

            if (al != null) {
                // Оповещение поверх статуса, в том же уведомлении.
                if (al.up) {
                    b.addAction(
                        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_good), "Принял", ackIntent(ctx, 31)).build()
                    )
                    val qa = quickAction(ctx)
                    if (qa != null) b.addAction(qa)
                    b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
                } else {
                    b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
                    b.addAction(action(ctx, "Выключить", MonitorService.ACTION_STOP, 12))
                }
                b.setSmallIcon(iconFor(al.level))
                    .setContentTitle(al.title)
                    .setContentText(al.text.substringBefore('\n'))
                    .setStyle(Notification.BigTextStyle().bigText(al.text + "\n\n" + sb.toString()))
                    .setColor(colorFor(al.level))
            } else {
                b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
                b.addAction(action(ctx, "Выключить", MonitorService.ACTION_STOP, 12))
                b.setSmallIcon(iconFor(level))
                    .setContentTitle(title)
                    .setContentText(if (info.quick) "Жду интернет · попыток ${info.quickCount}" else text)
                    .setStyle(Notification.BigTextStyle().bigText(sb.toString()))
                    .setColor(colorFor(level))
            }
            // Таймер «так уже N мин» идёт сам, уведомление для него обновлять не нужно.
            if (pending) {
                b.setShowWhen(true)
                b.setWhen(r!!.time)
                b.setSubText(Texts.transportLabel(r.transport) + " · перепроверяю")
            } else if (info.stateSince > 0L && info.stable != null) {
                b.setShowWhen(true)
                b.setWhen(info.stateSince)
                b.setUsesChronometer(true)
                b.setSubText(
                    (if (r != null) Texts.transportLabel(r.transport) + " · " else "") +
                        (if (info.stable) "в норме уже" else "нормального нет уже")
                )
            } else if (r != null) {
                b.setShowWhen(true)
                b.setWhen(r.time)
                b.setSubText(Texts.transportLabel(r.transport))
            } else {
                b.setShowWhen(false)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Build.VERSION.SDK_INT < 26) {
            applyLegacySound(ctx, b, channel, fresh)
        }
        return b.build()
    }

    /** До Android 8 каналов нет: звук и важность задаются самим уведомлением. */
    private fun applyLegacySound(ctx: Context, b: Notification.Builder, channel: String, fresh: Boolean) {
        if (!fresh || channel == CH_QUIET || channel == CH_STATUS) {
            b.setPriority(Notification.PRIORITY_LOW)
        } else if (channel == CH_LOST) {
            b.setPriority(Notification.PRIORITY_DEFAULT)
            b.setDefaults(Notification.DEFAULT_ALL)
        } else {
            b.setPriority(Notification.PRIORITY_HIGH)
            val uri = soundUriOrNull(ctx)
            if (uri != null) b.setSound(uri, soundAttrs(Prefs.loud(ctx)))
            if (Prefs.vibrate(ctx)) b.setVibrate(VIBRATION)
        }
    }

    // ---------- оповещения ----------

    private fun ackIntent(ctx: Context, req: Int): PendingIntent = PendingIntent.getService(
        ctx, req,
        Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_ACK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Кнопка быстрого действия: открыть выбранное приложение или ссылку. */
    private fun quickAction(ctx: Context): Notification.Action? {
        val kind = Prefs.actionKind(ctx)
        val target = Prefs.actionTarget(ctx)
        if (kind.isEmpty() || target.isEmpty()) return null
        val intent: Intent = try {
            when (kind) {
                "app" -> ctx.packageManager.getLaunchIntentForPackage(target)
                "url" -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 21, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = Prefs.actionLabel(ctx).ifEmpty { target }
        val short = if (label.length > 22) label.substring(0, 21) + "…" else label
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_good), "Открыть: $short", pi).build()
    }

    fun alertUp(ctx: Context, r: ProbeResult, wasMs: Long, again: Boolean = false) {
        val sb = StringBuilder(Texts.summary(r))
        if (r.net.isNotEmpty()) sb.append("\nСеть: ").append(r.net)
        val load = Texts.loadNote(r)
        if (load.isNotEmpty()) sb.append("\n").append(load)
        if (wasMs > 5_000L) sb.append("\nНе было нормального интернета: ").append(Texts.duration(wasMs))
        val title = if (again) "Нормальный интернет есть · напоминание" else "Нормальный интернет появился"
        post(ctx, true, r.level, title, sb.toString())
    }

    fun alertDown(ctx: Context, r: ProbeResult, wasMs: Long) {
        val title = when (r.level) {
            Level.OFFLINE -> "Сеть пропала"
            Level.NONE -> if (Texts.upOnly(r)) "Отправка почти не идёт" else "Интернет пропал"
            else -> "Интернет стал хуже нужного"
        }
        val sb = StringBuilder(Texts.summary(r))
        val cause = Texts.causeFor(r)
        if (cause.isNotEmpty()) sb.append("\n").append(cause)
        if (wasMs > 5_000L) sb.append("\nНормальный интернет был: ").append(Texts.duration(wasMs))
        post(ctx, false, r.level, title, sb.toString())
    }

    /** Пробное оповещение — чтобы услышать выбранный звук. */
    fun testAlert(ctx: Context) {
        ensureChannels(ctx)
        // Только про сам сигнал: цифры последней проверки тут запутывают, ведь это не результат измерения.
        val text = if (quietNow(ctx)) {
            "Пробное оповещение. Сейчас тихие часы — без звука и вибрации."
        } else {
            "Пробное оповещение. Так прозвучит сигнал, когда интернет появится."
        }
        post(ctx, true, Level.GOOD, "Проверка оповещения", text, TEST_SHOW_MS)
    }

    /** Подтверждение, когда служба не работает: убирает одиночное уведомление. */
    fun cancelAlert(ctx: Context) {
        alert = null
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ID_STATUS)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun post(ctx: Context, up: Boolean, level: Int, title: String, text: String, showMs: Long = ALERT_SHOW_MS) {
        val quiet = quietNow(ctx)
        val channel = when {
            quiet -> CH_QUIET
            up -> ensureAlertChannel(ctx)
            else -> CH_LOST
        }
        val svc = MonitorService.instance
        if (MonitorService.running && svc != null) {
            // Служба работает: обновляем её единственное уведомление, отдельного не создаём.
            alert = AlertState(up, level, title, text, System.currentTimeMillis() + showMs)
            svc.postAlert(channel)
            // Когда время показа выйдет, уведомление вернётся к обычному статусу.
            ui.postDelayed({ MonitorService.requestRefresh(ctx.applicationContext) }, showMs + 500L)
            return
        }
        // Службы нет (например, пробное оповещение при выключенном слежении): одно самостоятельное уведомление.
        val b = builder(ctx, channel)
            .setSmallIcon(iconFor(level))
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(colorFor(level))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
        if (up) {
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_good), "Принял", ackIntent(ctx, 31)).build()
            )
            val qa = quickAction(ctx)
            if (qa != null) b.addAction(qa)
            b.setDeleteIntent(ackIntent(ctx, 32))
        }
        if (Build.VERSION.SDK_INT < 26) applyLegacySound(ctx, b, channel, true)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_STATUS, b.build())
        } catch (e: Exception) {
            // нет разрешения на уведомления — молча пропускаем
        }
    }
}
