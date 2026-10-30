package expo.modules.netcheck

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фоновая служба: периодически измеряет скорость и обновляет уведомление в шторке.
 * Проверки запускаются: по таймеру, при смене сети, при включении экрана, по кнопке.
 * Когда нормального интернета нет, служба «ждёт» его частыми лёгкими запросами и держит
 * процессор бодрствующим, поэтому о появлении интернета узнаёт за несколько секунд даже при погашенном экране.
 */
class MonitorService : Service() {

    companion object {
        const val ACTION_START = "expo.modules.netcheck.START"
        const val ACTION_CHECK = "expo.modules.netcheck.CHECK"
        const val ACTION_STOP = "expo.modules.netcheck.STOP"
        const val ACTION_ACK = "expo.modules.netcheck.ACK"
        const val ACTION_REFRESH = "expo.modules.netcheck.REFRESH"
        private const val MAX_REPEATS = 20
        private const val ACTION_ALARM = "expo.modules.netcheck.ALARM"
        private const val QUICK_SEC = 5
        private const val QUICK_COOLDOWN_MS = 30_000L
        private const val LOCK_MS = 10L * 60L * 1000L
        // Пока на экране «Принял», плановые перерисовки уведомления — не чаще одной за это время.
        private const val ACK_TICK_MS = 5_000L
        // Stable state is confirmation state, not a copy of the latest probe.
        private const val STATE_PREFS = "internetcheck_prefs"
        private const val KEY_STABLE_USABLE = "stableUsable"
        private const val KEY_SERVICE_STOPPED = "serviceStopped"

        @Volatile
        var running: Boolean = false

        @Volatile
        var instance: MonitorService? = null

        fun settingsChanged(recheck: Boolean = false) {
            val svc = instance ?: return
            svc.handler.post { if (instance === svc && running) svc.applySettings(recheck) }
        }

        /** Доставляет команду уже работающему экземпляру, без фонового startService race. */
        fun requestRefresh(ctx: Context) {
            val svc = instance ?: return
            svc.handler.post { if (instance === svc && running) svc.refreshNotification(Runner.isBusy()) }
        }

        fun canStart(ctx: Context): Boolean {
            val notifications = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notifications != null && !notifications.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false
            return true
        }

        fun start(ctx: Context): Boolean {
            if (!canStart(ctx)) {
                Prefs.setStartupIssue(ctx, "Уведомления запрещены. Разрешите их в настройках Android и включите слежение.")
                return false
            }
            return try {
                val i = Intent(ctx, MonitorService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                true
            } catch (e: Exception) {
                Prefs.setStartupIssue(ctx, "Android не разрешил фоновый запуск. Откройте приложение и включите слежение вручную.")
                android.util.Log.w("InternetCheck", "Foreground service start rejected", e)
                false
            }
        }

        fun stop(ctx: Context) {
            // onDestroy also records this, but mark it before stopService so an
            // explicit stop cannot make the next run count the gap as uptime.
            statePrefs(ctx).edit().putBoolean(KEY_SERVICE_STOPPED, true).apply()
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        private fun statePrefs(ctx: Context) =
            ctx.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        /** Подтверждение оповещения: останавливает повторы и убирает уведомление. */
        fun requestAck(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.handler.post {
                    if (instance === svc && running) {
                        svc.stopRepeat(true)
                        if (!Prefs.enabled(svc)) svc.stopSelf()
                    }
                }
            } else {
                Notifier.cancelAlert(ctx)
            }
        }

        /** Просит работающую службу проверить интернет сейчас. */
        fun requestCheck(ctx: Context, onUnavailable: (() -> Unit)? = null): Boolean {
            if (instance == null || !running) return false
            val app = ctx.applicationContext
            // Эта очередь не очищается onDestroy старого экземпляра службы.
            return Handler(Looper.getMainLooper()).post {
                val current = instance
                if (current != null && running) {
                    current.checkNow()
                } else if (onUnavailable != null) {
                    onUnavailable()
                } else {
                    Thread {
                        try { Runner.checkOnce(app) } catch (_: Throwable) { }
                    }.start()
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var screenOn = true
    private var stableUsable: Boolean? = null
    private var stateSince = 0L
    private var pendingFlips = 0
    private var lastResult: ProbeResult? = null
    private var nextAt = 0L
    private var recheckRequested = false
    private var quickMode = false
    private var fromQuick = false
    private var quickCooldownUntil = 0L
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var watchLock: PowerManager.WakeLock? = null
    private var repeating = false
    private var repeatCount = 0
    private var lastAlert: ProbeResult? = null
    private var lastAlertWas = 0L
    // Не возвращаем живое уведомление на тихий канал: это обрывает системный звук.
    private var notificationChannel = Notifier.CH_STATUS
    // Когда уведомление публиковалось в последний раз (elapsedRealtime) — для ограничения перерисовок.
    private var lastPostAt = 0L
    // Сколько задач этой службы ждёт завершения в рабочем потоке (только главный поток).
    private var pendingWork = 0

    private val trafficMeter = RealtimeTraffic()
    private val trafficSampler = TrafficSampler(interfaceNames = {
        try {
            TrafficSampler.internetInterfaceNames(
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            )
        } catch (_: Exception) {
            emptyList()
        }
    })
    private var trafficRates = RealtimeTraffic.Rates()
    private var trafficSource = "unknown"
    private val trafficTick = object : Runnable {
        override fun run() {
            if (!running) return
            sampleTraffic()
            tickRefresh(Runner.isBusy())
            handler.postDelayed(this, 1000L)
        }
    }

    private val tick = Runnable { onTimer() }
    private val debounced = Runnable { checkNow() }

    /** Пока идёт проверка, обновляет уведомление, чтобы цифры менялись на глазах. */
    private val liveTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (Runner.isBusy()) {
                tickRefresh(true)
                handler.postDelayed(this, 700L)
            }
        }
    }

    /** Повтор оповещения «интернет появился», пока пользователь не подтвердит. */
    private val repeatRun = object : Runnable {
        override fun run() {
            if (!running || !repeating) return
            repeatCount += 1
            val r = lastAlert
            if (r == null || repeatCount > MAX_REPEATS || !AlertPolicy.repeatAllowed(
                    Prefs.alerts(this@MonitorService), Prefs.repeat(this@MonitorService),
                    Notifier.quietNow(this@MonitorService), stableUsable == true
                )) {
                stopRepeat(false)
                return
            }
            Notifier.alertUp(this@MonitorService, r, lastAlertWas, true)
            handler.postDelayed(this, Prefs.repeatSec(this@MonitorService) * 1000L)
        }
    }

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onTimer()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreen(true)
                Intent.ACTION_SCREEN_OFF -> onScreen(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        Notifier.ensureChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = pm.isInteractive
        lastResult = Prefs.last(this)
        val statePrefs = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val wasStopped = statePrefs.getBoolean(KEY_SERVICE_STOPPED, false)
        statePrefs.edit().remove(KEY_SERVICE_STOPPED).apply()
        if (wasStopped) {
            // Monitoring was explicitly stopped (or the service was destroyed).
            // Do not turn the inactive interval into availability on restart.
            statePrefs.edit().remove(KEY_STABLE_USABLE).apply()
            Prefs.setStateSince(this, 0L)
            stableUsable = null
            stateSince = 0L
            pendingFlips = 0
        } else if (statePrefs.contains(KEY_STABLE_USABLE)) {
            stableUsable = statePrefs.getBoolean(KEY_STABLE_USABLE, false)
            stateSince = Prefs.stateSince(this)
        } else {
            // One-time migration for installations that predate confirmed-state persistence.
            // Never overwrite a saved stable state with an unusable latest probe.
            val migrated = lastResult
            if (migrated != null) {
                stableUsable = Target.usable(this, migrated)
                stateSince = Prefs.stateSince(this).takeIf { it > 0L } ?: migrated.time
                statePrefs.edit().putBoolean(KEY_STABLE_USABLE, stableUsable!!).apply()
                Prefs.setStateSince(this, stateSince)
            } else {
                stableUsable = null
                stateSince = Prefs.stateSince(this)
            }
        }
        Live.stable = stableUsable?.let { if (it) 1 else 0 } ?: -1
        Live.quickCount = 0
        Live.quickAt = 0L

        registerCompat(alarmReceiver, IntentFilter(ACTION_ALARM), false)
        val sf = IntentFilter()
        sf.addAction(Intent.ACTION_SCREEN_ON)
        sf.addAction(Intent.ACTION_SCREEN_OFF)
        registerCompat(screenReceiver, sf, true)
        registerNetwork()
        sampleTraffic()
        handler.postDelayed(trafficTick, 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!showForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_SERVICE_STOPPED, true).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CHECK -> checkNow()
            ACTION_REFRESH -> refreshNotification(Runner.isBusy())
            ACTION_ACK -> {
                stopRepeat(true)
                if (!Prefs.enabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                Prefs.setEnabled(this, true)
                checkNow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        Notifier.clearAlert()
        cancelScheduled()
        handler.removeCallbacksAndMessages(null)
        repeating = false
        releaseWatchLock()
        Live.quickMode = false
        Live.stable = -1
        pushSurfaces()
        try {
            unregisterReceiver(alarmReceiver)
        } catch (e: Exception) {
            // ignore
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // ignore
        }
        val cb = netCallback
        if (cb != null) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // ignore
            }
        }
        worker.shutdownNow()
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_STOPPED, true).apply()
        Prefs.setNextCheckAt(this, 0L)
        super.onDestroy()
    }

    // ---------- уведомление ----------

    /** Обновляет виджет и плитку быстрых настроек. */
    private fun pushSurfaces() {
        try {
            NetWidget.refresh(this)
            NetTileService.requestUpdate(this)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun startRepeat(r: ProbeResult, was: Long) {
        if (!Prefs.repeat(this) || Notifier.quietNow(this)) return
        lastAlert = r
        lastAlertWas = was
        repeatCount = 0
        repeating = true
        acquireWatchLock()
        handler.removeCallbacks(repeatRun)
        handler.postDelayed(repeatRun, Prefs.repeatSec(this) * 1000L)
    }

    private fun stopRepeat(cancelNotification: Boolean) {
        repeating = false
        handler.removeCallbacks(repeatRun)
        if (cancelNotification) {
            // Оповещение живёт в том же уведомлении: убираем его, статус остаётся.
            Notifier.clearAlert()
            notificationChannel = Notifier.CH_STATUS
            refreshNotification(Runner.isBusy())
        }
        if (!quickMode) releaseWatchLock()
    }

    private fun sampleTraffic() {
        val cm = try {
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        } catch (_: Throwable) {
            null
        }
        val active = try { cm?.activeNetwork?.toString() ?: "none" } catch (_: Throwable) { "none" }
        val networkKey = "$active;transport=${Net.transport(this)}"
        val sample = trafficSampler.sample(networkKey)
        trafficSource = sample.source
        trafficRates = trafficMeter.update(
            sample.rx, sample.tx, SystemClock.elapsedRealtime(), sample.source
        )
    }

    private fun info(): Notifier.Info {
        val connection = Net.connectionStatus(this)
        return Notifier.Info(
            stateSince = stateSince,
            stable = stableUsable,
            nextAt = nextAt,
            quick = quickMode,
            quickCount = Live.quickCount,
            trafficLine = RealtimeTraffic.line(connection, trafficRates),
            trafficRates = trafficRates,
            connection = connection,
            trafficSource = trafficSource
        )
    }

    private fun showForeground(): Boolean {
        val n = Notifier.buildStatus(this, lastResult, Runner.isBusy(), info(), notificationChannel)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_STATUS, n)
            }
            Prefs.setStartupIssue(this, "")
            return true
        } catch (e: Exception) {
            Prefs.setStartupIssue(this, "Не удалось показать фоновое уведомление. Проверьте разрешения Android.")
            Prefs.setEnabled(this, false)
            android.util.Log.w("InternetCheck", "Foreground notification rejected", e)
            stopSelf()
            return false
        }
    }

    /** Оповещение: обновляет единственное уведомление на канале оповещения (со звуком), отдельного не создаёт. */
    fun postAlert(channel: String) {
        try {
            notificationChannel = channel
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Notifier.ID_STATUS, Notifier.buildStatus(this, lastResult, false, info(), channel, true))
            lastPostAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // нет разрешения на уведомления — молча пропускаем
        }
    }

    private fun refreshNotification(checking: Boolean) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Notifier.ID_STATUS, Notifier.buildStatus(this, lastResult, checking, info(), notificationChannel))
            lastPostAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Плановое обновление по таймеру (скорость каждую секунду, ход проверки). Пока на экране кнопка
     * «Принял», уведомление не перерисовывается чаще раза в ACK_TICK_MS: перерисовка в момент касания
     * срывает нажатие. События (результат проверки, подтверждение) обновляют уведомление сразу.
     */
    private fun tickRefresh(checking: Boolean) {
        if (AlertPolicy.skipTickRefresh(Notifier.ackPending(), SystemClock.elapsedRealtime(), lastPostAt, ACK_TICK_MS)) return
        refreshNotification(checking)
    }

    // ---------- проверка ----------

    private fun applySettings(recheck: Boolean) {
        if (Notifier.dismissDisabledAlert(Prefs.alerts(this), Prefs.alertsLost(this))) {
            notificationChannel = Notifier.CH_STATUS
        }
        if (Notifier.quietNow(this)) notificationChannel = Notifier.CH_QUIET
        if (!AlertPolicy.repeatAllowed(Prefs.alerts(this), Prefs.repeat(this), Notifier.quietNow(this), stableUsable == true)) {
            stopRepeat(false)
        } else if (repeating) {
            handler.removeCallbacks(repeatRun)
            handler.postDelayed(repeatRun, Prefs.repeatSec(this) * 1000L)
        }
        if (recheck) {
            // Новое условие не является событием восстановления сети: создаём новую базовую проверку.
            stopRepeat(true)
            stableUsable = null
            pendingFlips = 0
            Live.stable = -1
            checkNow()
            return
        }
        if (!Runner.isBusy()) {
            val r = lastResult
            if (r != null) {
                val usable = Target.usable(this, r)
                val quick = Prefs.fastWatch(this) && r.level == Level.NONE && !Texts.upOnly(r)
                scheduleNext(nextDelaySec(r, quick, usable), quick)
            }
        }
        refreshNotification(Runner.isBusy())
    }

    private fun onTimer() {
        if (quickMode) quickPing() else checkNow()
    }

    private fun checkNow() {
        if (!running) return
        if (Runner.isBusy()) {
            if (pendingWork > 0) {
                // Проверку ведёт эта служба: по её завершению onResult выполнит повторную проверку.
                recheckRequested = true
            } else {
                // Занято разовой проверкой из приложения или хвостом предыдущего экземпляра службы:
                // сообщения о её завершении сюда не придёт, поэтому без таймера слежение застыло бы.
                scheduleNext(2, quickMode)
            }
            return
        }
        cancelScheduled()
        refreshNotification(true)
        handler.removeCallbacks(liveTick)
        handler.postDelayed(liveTick, 500L)
        // Идёт подтверждение падения: проверяем через другой сервер, чтобы сбой одного не давал ложную тревогу.
        val confirm = pendingFlips > 0
        pendingWork += 1
        worker.execute {
            val r: ProbeResult? = try {
                Runner.checkOnce(applicationContext, confirm)
            } catch (e: Throwable) {
                null
            }
            handler.post {
                pendingWork = (pendingWork - 1).coerceAtLeast(0)
                onResult(r)
            }
        }
    }

    /** Лёгкая проверка в режиме ожидания: если кто-то ответил — сразу делаем полное измерение. */
    private fun quickPing() {
        if (!running) return
        if (!Prefs.fastWatch(this)) {
            applySettings(false)
            return
        }
        if (Runner.isBusy()) {
            scheduleNext(2, true)
            return
        }
        cancelScheduled()
        pendingWork += 1
        worker.execute {
            val ok: Boolean? = try {
                Runner.pingOnce(applicationContext)
            } catch (e: Throwable) {
                null
            }
            handler.post {
                pendingWork = (pendingWork - 1).coerceAtLeast(0)
                onQuick(ok)
            }
        }
    }

    private fun onQuick(ok: Boolean?) {
        if (!running) return
        if (!Prefs.fastWatch(this)) {
            applySettings(false)
            return
        }
        if (recheckRequested) {
            recheckRequested = false
            checkNow()
            return
        }
        if (ok == true && System.currentTimeMillis() >= quickCooldownUntil) {
            fromQuick = true
            checkNow()
            return
        }
        refreshNotification(false)
        scheduleNext(QUICK_SEC, true)
    }

    private fun nextDelaySec(r: ProbeResult, quick: Boolean, usable: Boolean): Int {
        val interval = Prefs.intervalSec(this)
        return when (r.level) {
            Level.OFFLINE -> 30 // о возвращении сети сообщит системный обратный вызов
            Level.NONE -> if (quick) QUICK_SEC else if (screenOn) 20 else 60
            Level.WEAK -> if (Prefs.fastWatch(this) && !usable) minOf(interval, 60) else minOf(interval, 120)
            // Интернет есть, но ниже выбранного порога — проверяем чаще, чтобы поймать улучшение.
            else -> if (!usable && Prefs.fastWatch(this)) minOf(interval, 45) else stretch(interval)
        }
    }

    /** Когда всё спокойно: на мобильной сети с экономией и при низком заряде проверяем реже. */
    private fun stretch(sec: Int): Int {
        var f = 1
        if (Power.lowBattery(this)) f *= 2
        if (Prefs.saveMobile(this) && Net.transport(this) == "cell") f *= 2
        return minOf(sec * f, 3600)
    }

    private fun onResult(r: ProbeResult?) {
        if (!running) return
        handler.removeCallbacks(liveTick)
        if (r == null) {
            scheduleNext(10, false)
            refreshNotification(false)
            return
        }
        lastResult = r
        var delay = 0

        val wasFromQuick = fromQuick
        fromQuick = false
        if (wasFromQuick && r.level <= Level.NONE) {
            // Сервер ответил, а данные не идут — не гоняем полные проверки каждые 5 секунд.
            quickCooldownUntil = System.currentTimeMillis() + QUICK_COOLDOWN_MS
        }

        val usable = Target.usable(this, r)
        val stable = stableUsable
        // Availability transitions describe when this result was processed, not when
        // the probe started/finished in a worker thread.
        val processedAt = System.currentTimeMillis()
        var flipPending = false
        var alerted = false // оповещение только что обновило уведомление — второй раз его сразу не трогаем, чтобы не оборвать звук
        if (stable == null) {
            setState(usable, processedAt)
        } else if (usable == stable) {
            pendingFlips = 0
        } else if (usable) {
            // Нормальный интернет появился — сообщаем сразу, полная проверка уже достаточное подтверждение.
            val was = if (stateSince > 0L) processedAt - stateSince else 0L
            setState(true, processedAt)
            if (Prefs.alerts(this)) {
                Notifier.alertUp(this, r, was)
                alerted = true
                startRepeat(r, was)
            }
        } else {
            pendingFlips += 1
            if (pendingFlips >= 2 || r.level == Level.OFFLINE) {
                val was = if (stateSince > 0L) processedAt - stateSince else 0L
                setState(false, processedAt)
                stopRepeat(false)
                if (Prefs.alertsLost(this)) {
                    Notifier.alertDown(this, r, was)
                    alerted = true
                }
            } else {
                flipPending = true // падение подтверждаем второй проверкой
            }
        }

        // «Жду интернет» — только когда данные не идут совсем; если скачивание в порядке, а плоха отправка, ждать нечего.
        val quick = !flipPending && Prefs.fastWatch(this) && r.level == Level.NONE && !Texts.upOnly(r)
        delay = if (flipPending) 6 else nextDelaySec(r, quick, usable)

        if (recheckRequested) {
            recheckRequested = false
            delay = minOf(delay, 3)
            scheduleNext(delay, false)
        } else {
            scheduleNext(delay, quick)
        }
        if (!alerted) refreshNotification(false)
        pushSurfaces()
    }

    private fun setState(usable: Boolean, time: Long) {
        stableUsable = usable
        stateSince = time
        pendingFlips = 0
        Notifier.clearAlert() // состояние сменилось — старое оповещение уже неактуально
        notificationChannel = Notifier.CH_STATUS
        Live.stable = if (usable) 1 else 0
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STABLE_USABLE, usable).apply()
        Prefs.setStateSince(this, time)
        Store.addEvent(this, time, usable)
    }

    // ---------- расписание ----------

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 7,
        Intent(ACTION_ALARM).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelScheduled() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(debounced)
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmIntent())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun acquireWatchLock() {
        try {
            var l = watchLock
            if (l == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                l = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "internetcheck:watch")
                l.setReferenceCounted(false)
                watchLock = l
            }
            l.acquire(LOCK_MS) // обновляется при каждом шаге, сам отпустится, если служба зависнет
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseWatchLock() {
        try {
            val l = watchLock
            if (l != null && l.isHeld) l.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Экран включён или идёт ожидание интернета — обычный таймер (процессор не спит, пока держится блокировка);
     * иначе — будильник, который будит процессор.
     */
    private fun scheduleNext(delaySec: Int, quick: Boolean) {
        cancelScheduled()
        quickMode = quick
        Live.quickMode = quick
        if (quick || repeating) acquireWatchLock() else releaseWatchLock()
        val delayMs = delaySec * 1000L
        nextAt = System.currentTimeMillis() + delayMs
        Prefs.setNextCheckAt(this, nextAt)
        if (screenOn || quick) {
            handler.postDelayed(tick, delayMs)
        } else {
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    alarmIntent()
                )
            } catch (e: Exception) {
                handler.postDelayed(tick, delayMs)
            }
        }
    }

    private fun keepSchedule() {
        if (Runner.isBusy()) return
        val rem = ((nextAt - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(1)
        scheduleNext(rem, quickMode)
    }

    private fun debouncedCheck(ms: Long) {
        handler.removeCallbacks(debounced)
        handler.postDelayed(debounced, ms)
    }

    private fun onScreen(on: Boolean) {
        screenOn = on
        if (on) {
            sampleTraffic()
            refreshNotification(Runner.isBusy())
            val last = lastResult
            // Пользователь взял телефон — показываем свежие данные.
            if (last == null || System.currentTimeMillis() - last.time > 30_000L) {
                debouncedCheck(1500L)
            } else {
                keepSchedule()
            }
        } else {
            keepSchedule()
        }
    }

    // ---------- события системы ----------

    private fun registerCompat(r: BroadcastReceiver, f: IntentFilter, exported: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, f, if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(r, f)
        }
    }

    private fun registerNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post { debouncedCheck(600L) }
                }

                override fun onLost(network: Network) {
                    handler.post { debouncedCheck(1000L) }
                }

                // Система сама подтвердила, что в сети есть интернет — проверяем сразу.
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        handler.post {
                            val lastAt = lastResult?.time ?: 0L
                            if (stableUsable != true && System.currentTimeMillis() - lastAt > 8_000L) {
                                debouncedCheck(600L)
                            }
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            // без обратного вызова остаётся проверка по таймеру
        }
    }
}
