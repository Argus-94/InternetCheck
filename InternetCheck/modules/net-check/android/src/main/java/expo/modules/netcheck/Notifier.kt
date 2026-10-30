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
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    const val CH_STATUS = "status"
    const val CH_LOST = "lost"
    const val CH_QUIET = "quiet_v2"
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

    /** Показана ли сейчас кнопка «Принял»: оповещение «интернет появился» ещё не подтверждено. */
    internal fun ackPending(): Boolean = activeAlert()?.up == true

    internal fun dismissDisabledAlert(upEnabled: Boolean, downEnabled: Boolean): Boolean {
        val current = activeAlert() ?: return false
        if (if (current.up) upEnabled else downEnabled) return false
        clearAlert()
        return true
    }

    internal fun statusChannel(channel: String, fresh: Boolean): String =
        if (!fresh && activeAlert() == null) CH_STATUS else channel

    /** Данные о состоянии службы для уведомления. */
    data class Info(
        val stateSince: Long,
        val stable: Boolean?,
        val nextAt: Long,
        val quick: Boolean,
        val quickCount: Int,
        val trafficLine: String = "Трафик · ↓ — · ↑ —",
        val trafficRates: RealtimeTraffic.Rates = RealtimeTraffic.Rates(),
        val connection: String = "",
        val trafficSource: String = ""
    )

    // ---------- каналы ----------

    /** Канал оповещения «интернет появился» зависит от звука и вибрации: у каналов их нельзя менять на лету. */
    fun alertChannelId(ctx: Context, up: Boolean = true): String {
        return AlertPolicy.channelKey(Prefs.soundUri(ctx), Prefs.loud(ctx), Prefs.vibrate(ctx), up)
    }

    private fun soundAttrs(loud: Boolean): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (loud) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun soundUriOrNull(ctx: Context): Uri? {
        val s = AlertPolicy.soundSetting(Prefs.soundUri(ctx))
        return when (s) {
            "silent" -> null
            "" -> Settings.System.DEFAULT_NOTIFICATION_URI
            else -> {
                // Uri.parse accepts relative/malformed-looking strings. Such a
                // value cannot identify an Android sound provider and otherwise
                // creates a channel that can never play its configured sound.
                val parsed = try { Uri.parse(s) } catch (_: Exception) { null }
                if (parsed?.scheme.isNullOrEmpty()) Settings.System.DEFAULT_NOTIFICATION_URI else parsed
            }
        }
    }

    /** False means Android will discard notification posts regardless of sound settings. */
    fun notificationsEnabled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24) return true
        return try {
            ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
        } catch (_: Exception) {
            // Do not suppress an alert on unusual test/OEM manager implementations.
            true
        }
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val st = NotificationChannel(CH_STATUS, "Состояние интернета", NotificationManager.IMPORTANCE_LOW)
        st.description = "Постоянное уведомление со статусом и проверкой в реальном времени"
        st.setShowBadge(false)
        st.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        st.setSound(null, null)
        st.enableVibration(false)
        nm.createNotificationChannel(st)

        val quiet = NotificationChannel(CH_QUIET, "Оповещения в тихие часы", NotificationManager.IMPORTANCE_LOW)
        quiet.description = "Те же оповещения, но без звука и вибрации — в часы, выбранные в настройках"
        quiet.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        quiet.setSound(null, null)
        quiet.enableVibration(false)
        nm.createNotificationChannel(quiet)

        try {
            nm.deleteNotificationChannel(CH_OLD_ALERTS)
        } catch (e: Exception) {
            // ignore
        }
        ensureAlertChannel(ctx)
        ensureAlertChannel(ctx, false)
    }

    /** Оба события используют выбранный звук. Активные и настроенные пользователем каналы не удаляем. */
    fun ensureAlertChannel(ctx: Context, up: Boolean = true): String {
        val id = alertChannelId(ctx, up)
        if (Build.VERSION.SDK_INT < 26) return id
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return id
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, if (up) "Интернет появился" else "Интернет пропал", NotificationManager.IMPORTANCE_HIGH)
            ch.description = if (up) "Сигнал, когда нормальный интернет появился" else "Сигнал, когда нормальный интернет пропал"
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            val uri = soundUriOrNull(ctx)
            if (uri == null) ch.setSound(null, null) else ch.setSound(uri, soundAttrs(Prefs.loud(ctx)))
            ch.enableVibration(Prefs.vibrate(ctx))
            if (Prefs.vibrate(ctx)) ch.vibrationPattern = VIBRATION
            // Alarm volume selects the audio stream, not permission to bypass DND.
            ch.setBypassDnd(false)
            nm.createNotificationChannel(ch)
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
        return AlertPolicy.quietNow(true, from, to, m)
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
        val al = if (live) null else activeAlert()
        // Кнопка «Принял» остаётся на месте и во время проверки: если набор кнопок меняется
        // посреди оповещения, нажатие попадает в другую кнопку («Проверить», «Выключить») или в пустоту.
        val ackShown = ackPending()
        val title = if (r == null) Texts.title(level) else Texts.titleFor(r)
        val usableNow = r != null && Target.usable(ctx, r)
        val pending = r != null && info.stable == true && !usableNow
        val effectiveChannel = statusChannel(channel, fresh)
        val b = builder(ctx, effectiveChannel)
            .setOngoing(true)
            .setOnlyAlertOnce(!fresh)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))

        val status: String
        val result: String
        val details = ArrayList<String>()
        val timing = ArrayList<String>()
        if (live) {
            status = liveTitle()
            result = "Проверка сейчас: " + liveLine()
            if (r != null) details.add("Предыдущая: ${Texts.titleFor(r)} · ${Texts.summary(r)}")
            b.setSmallIcon(R.drawable.ic_stat_wait)
                .setColor(colorFor(Level.UNKNOWN))
                .setProgress(100, Live.progress.coerceIn(0, 100), false)
                .setShowWhen(false)
                .setSubText(liveTitle())
        } else {
            status = al?.title ?: if (pending) "Перепроверяю ухудшение · $title" else title
            result = if (r == null) "Идёт первая проверка…" else "Последняя проверка: " + Texts.summary(r)
            if (al != null) details.add(al.text)
            if (r != null) {
                details.add(Texts.hintFor(r))
                val cause = Texts.causeFor(r)
                if (cause.isNotEmpty()) details.add(cause)
                val load = Texts.loadNote(r)
                if (load.isNotEmpty()) details.add(load)
                val calls = Texts.callsText(r)
                if (calls.isNotEmpty()) details.add(calls)
                if (!usableNow && r.level > Level.NONE) {
                    details.add(if (Prefs.onlyWifi(ctx) && r.transport != "wifi" && r.transport != "eth")
                        "Для оповещений выбрано «только Wi‑Fi»."
                    else "Ниже выбранного порога нормального интернета.")
                }
            }
            b.setSmallIcon(iconFor(al?.level ?: level)).setColor(colorFor(al?.level ?: level))
            // Системный таймер сохраняется в заголовке, не отнимая место у единиц скорости.
            when {
                pending -> b.setShowWhen(true).setWhen(r!!.time).setSubText("перепроверяю")
                info.stateSince > 0L && info.stable != null -> b.setShowWhen(true)
                    .setWhen(info.stateSince).setUsesChronometer(true)
                    .setSubText(if (info.stable) "в норме уже" else "нормального нет уже")
                r != null -> b.setShowWhen(true).setWhen(r.time)
                else -> b.setShowWhen(false)
            }
        }
        if (al != null) b.setSubText(al.title)
        if (r != null) timing.add("Проверено: ${clockSec(r.time)}")
        if (info.quick) {
            timing.add("Жду интернет · каждые 5 с · попыток ${info.quickCount}")
        } else if (info.nextAt > System.currentTimeMillis()) {
            timing.add("Следующая: ${clockSec(info.nextAt)}")
        }

        if (ackShown) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_good),
                "Принял", ackIntent(ctx, 31)).build())
            val qa = quickAction(ctx)
            if (qa != null) b.addAction(qa)
            b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
        } else {
            b.addAction(action(ctx, "Проверить", MonitorService.ACTION_CHECK, 11))
            b.addAction(action(ctx, "Выключить", MonitorService.ACTION_STOP, 12))
        }

        // Ни название VPN/оператора, ни статус не конкурируют со скоростью за одну строку.
        // Каждое направление — отдельная колонка: число и единица всегда в одном TextView.
        val compact = RemoteViews(ctx.packageName, R.layout.nc_notification_compact)
        val expanded = RemoteViews(ctx.packageName, R.layout.nc_notification_expanded)
        val quality = NotificationQualityText.pair(r, checking)
        for (view in arrayOf(compact, expanded)) {
            view.setTextViewText(R.id.nc_traffic_down, NotificationTrafficText.down(info.trafficRates))
            view.setTextViewText(R.id.nc_traffic_up, NotificationTrafficText.up(info.trafficRates))
            view.setTextViewText(R.id.nc_status, status)
            view.setTextViewText(R.id.nc_quality, if (view === compact) quality.replace("\n", " · ") else quality)
            view.setContentDescription(R.id.nc_quality, quality)
            view.setContentDescription(R.id.nc_traffic_down, "Приём: " + RealtimeTraffic.speed(info.trafficRates.down))
            view.setContentDescription(R.id.nc_traffic_up, "Отправка: " + RealtimeTraffic.speed(info.trafficRates.up))
        }
        val connection = info.connection.ifEmpty { Net.connectionStatus(ctx) }
        val sourceLabel = when {
            info.trafficSource.startsWith("sysfs-interfaces:") -> "Физические сети · ≈1 с"
            connection.contains("VPN", ignoreCase = true) -> "≈1 с · VPN: возможна погрешность"
            else -> "Счётчики Android · ≈1 с"
        }
        val connectionText = "Сейчас: $connection\n$sourceLabel"
        expanded.setTextViewText(R.id.nc_connection, connectionText)
        expanded.setTextViewText(R.id.nc_result, result)
        expanded.setTextViewText(R.id.nc_details, details.filter { it.isNotEmpty() }.joinToString("\n"))
        expanded.setViewVisibility(R.id.nc_details, if (details.isEmpty()) View.GONE else View.VISIBLE)
        expanded.setTextViewText(R.id.nc_timing, timing.joinToString(" · "))
        expanded.setViewVisibility(R.id.nc_timing, if (timing.isEmpty()) View.GONE else View.VISIBLE)
        b.setContentTitle(NotificationTrafficText.fallback(info.trafficRates))
            .setContentText("$status · ${quality.replace('\n', ' ')} · $connection")
            .setCustomContentView(compact)
            // Android controls expansion of the top-ranked notification; there is
            // no public "start collapsed" API. Keep its big layout compact too,
            // for every mode, including a previously saved detailed preference.
            .setCustomBigContentView(compact.clone())
            .setStyle(Notification.DecoratedCustomViewStyle())

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Build.VERSION.SDK_INT < 26) {
            applyLegacySound(ctx, b, effectiveChannel, fresh)
        }
        return b.build()
    }

    /** До Android 8 каналов нет: звук и важность задаются самим уведомлением. */
    private fun applyLegacySound(ctx: Context, b: Notification.Builder, channel: String, fresh: Boolean) {
        if (channel == CH_QUIET || channel == CH_STATUS) {
            b.setPriority(Notification.PRIORITY_LOW)
        } else {
            b.setPriority(Notification.PRIORITY_HIGH)
            val uri = soundUriOrNull(ctx)
            if (uri != null) b.setSound(uri, soundAttrs(Prefs.loud(ctx)))
            if (Prefs.vibrate(ctx)) b.setVibrate(VIBRATION)
        }
    }

    // ---------- оповещения ----------

    /**
     * Подтверждение идёт широковещательным намерением, а не запуском службы: приёмник срабатывает
     * сразу и не зависит от ограничений фонового запуска, не пересобирает уведомление службы и не
     * поднимает службу, когда слежение выключено (пробное оповещение, смахивание уведомления).
     */
    private fun ackIntent(ctx: Context, req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req,
        Intent(ctx, AckReceiver::class.java).setAction(MonitorService.ACTION_ACK),
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
        // A blocked app notification cannot produce sound, vibration, or a
        // visible alert. In particular, do not leave an in-memory alert which
        // would make later status updates appear to be in alert mode.
        if (!notificationsEnabled(ctx)) {
            alert = null
            return
        }
        val quiet = quietNow(ctx)
        val channel = when {
            quiet -> CH_QUIET
            up -> ensureAlertChannel(ctx)
            else -> ensureAlertChannel(ctx, false)
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
            .setColor(colorFor(level))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
        // No BigTextStyle: standalone/test alerts also stay short in every mode.
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(showMs)
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
