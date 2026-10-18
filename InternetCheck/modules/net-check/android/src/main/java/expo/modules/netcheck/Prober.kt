package expo.modules.netcheck

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Измеряет РЕАЛЬНУЮ скорость: скачивает небольшой файл (до ~250 КБ) и считает КБ/с.
 * Серверы опрашиваются «эстафетой»: если первый не ответил за 1,5 с, параллельно стартует второй,
 * затем третий. Побеждает тот, кто первым отдал данные, остальные отключаются.
 */
object Prober {
    private val URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=256000",
        "https://proof.ovh.net/files/10Mb.dat",
        "https://speed.hetzner.de/100MB.bin"
    )
    private val BUDGETS_MS = intArrayOf(5000, 4000, 3000)
    private const val STAGGER_MS = 1500L
    private const val MAX_BYTES = 256_000L

    // Выгрузка: отправляем 512 КБ. Скачивать может быть нормально, а отправлять — нет.
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val UP_BYTES = 512_000
    private const val UP_BUDGET_MS = 8000L
    private const val UP_BUFFER_ALLOWANCE = 300_000L // сколько ОС может «проглотить» в буфер

    private class Attempt(val url: String, val budgetMs: Int) {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var bytes: Long = 0L
        @Volatile var t0: Long = 0L
        @Volatile var headersAt: Long = -1L
        @Volatile var endAt: Long = -1L
        @Volatile var error: String = ""
        @Volatile var cancelled: Boolean = false
        @Volatile var thread: Thread? = null
    }

    private fun startAttempt(url: String, budgetMs: Int, winner: AtomicReference<Attempt?>): Attempt {
        val a = Attempt(url, budgetMs)
        a.t0 = SystemClock.elapsedRealtime()
        val th = Thread {
            try {
                val full = if (url.contains("?")) url + "&r=" + System.nanoTime() else url
                val c = URL(full).openConnection() as HttpURLConnection
                a.conn = c
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.useCaches = false
                c.instanceFollowRedirects = true
                c.setRequestProperty("Accept-Encoding", "identity")
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Connection", "close")
                val code = c.responseCode
                a.headersAt = SystemClock.elapsedRealtime()
                if (code in 200..299) {
                    val ins = c.inputStream
                    val buf = ByteArray(16 * 1024)
                    val deadline = a.t0 + budgetMs
                    var first = true
                    while (a.bytes < MAX_BYTES && SystemClock.elapsedRealtime() < deadline && !a.cancelled) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        a.bytes = a.bytes + n
                        if (winner.get() == null) winner.compareAndSet(null, a)
                        if (winner.get() !== a) {
                            a.cancelled = true // другой сервер ответил раньше
                            break
                        }
                        if (first) {
                            first = false
                            Live.latencyMs = (a.headersAt - a.t0).toInt().coerceAtLeast(0)
                        }
                        Live.onDown(a.bytes, a.headersAt, a.t0, budgetMs, MAX_BYTES)
                    }
                } else {
                    a.error = "HTTP $code"
                }
            } catch (e: Exception) {
                if (!a.cancelled) a.error = e.javaClass.simpleName
            } finally {
                a.endAt = SystemClock.elapsedRealtime()
                try {
                    a.conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        th.isDaemon = true
        a.thread = th
        th.start()
        return a
    }

    private class UpAttempt {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var t0: Long = 0L
        @Volatile var startAt: Long = -1L
        @Volatile var endAt: Long = -1L
        @Volatile var written: Long = 0L
        @Volatile var done: Boolean = false
        @Volatile var error: String = ""
    }

    private fun runUpload(): UpAttempt {
        val a = UpAttempt()
        a.t0 = SystemClock.elapsedRealtime()
        val th = Thread {
            try {
                val c = URL(UP_URL).openConnection() as HttpURLConnection
                a.conn = c
                c.connectTimeout = 3000
                c.readTimeout = 5000
                c.useCaches = false
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(UP_BYTES)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.setRequestProperty("Connection", "close")
                val out = c.outputStream
                a.startAt = SystemClock.elapsedRealtime()
                val buf = ByteArray(16 * 1024)
                java.util.Random().nextBytes(buf)
                var left = UP_BYTES
                val deadline = a.t0 + UP_BUDGET_MS
                while (left > 0 && SystemClock.elapsedRealtime() < deadline) {
                    val n = minOf(buf.size, left)
                    out.write(buf, 0, n)
                    a.written = a.written + n
                    Live.onUp(a.written, a.startAt, UP_BYTES, UP_BUFFER_ALLOWANCE)
                    left -= n
                }
                if (left == 0) {
                    out.flush()
                    val code = c.responseCode // приходит, когда сервер получил всё
                    if (code in 200..299) a.done = true else a.error = "HTTP $code"
                }
            } catch (e: Exception) {
                a.error = e.javaClass.simpleName
            } finally {
                a.endAt = SystemClock.elapsedRealtime()
                try {
                    a.conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        th.isDaemon = true
        th.start()
        th.join(UP_BUDGET_MS + 6000L)
        if (th.isAlive) {
            if (a.error.isEmpty()) a.error = "timeout"
            if (a.endAt < 0) a.endAt = SystemClock.elapsedRealtime()
            try {
                a.conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
        return a
    }

    /** Скорость выгрузки в байтах/с; -1, если измерить не удалось (например, сервер отказал). */
    private fun upSpeed(a: UpAttempt): Long {
        val end = if (a.endAt > 0) a.endAt else SystemClock.elapsedRealtime()
        val from = if (a.startAt > 0) a.startAt else a.t0
        val el = maxOf(end - from, 50L)
        if (a.done) return UP_BYTES * 1000L / el
        // Ни одного байта не ушло (сервер не принял соединение) — это ошибка сервера, а не «нет выгрузки».
        if (a.written == 0L) return -1L
        // Обрыв, сброс соединения, отказ сервера — тоже не скорость: не выдаём это за «данные не идут».
        val slow = a.error.isEmpty() || a.error == "timeout" || a.error.contains("Timeout")
        if (!slow) return -1L
        // Если за весь бюджет ушло меньше, чем «проглатывает» буфер ОС, вычитание дало бы 0 — берём как есть.
        if (a.written <= UP_BUFFER_ALLOWANCE) return a.written * 1000L / el
        return (a.written - UP_BUFFER_ALLOWANCE) * 1000L / el
    }

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        ""
    }

    /** Эстафета серверов. Возвращает победителя (или null) и все попытки. */
    private fun raceDownloads(): Pair<Attempt?, List<Attempt>> {
        val winner = AtomicReference<Attempt?>(null)
        val atts = ArrayList<Attempt>()
        val raceStart = SystemClock.elapsedRealtime()
        var idx = 0
        while (idx < URLS.size && winner.get() == null) {
            val a = startAttempt(URLS[idx], BUDGETS_MS[idx], winner)
            atts.add(a)
            val last = idx == URLS.size - 1
            val until = SystemClock.elapsedRealtime() + (if (last) BUDGETS_MS[idx] + 1500L else STAGGER_MS)
            while (SystemClock.elapsedRealtime() < until && winner.get() == null) {
                if (atts.all { it.endAt >= 0L }) break // все уже закончились ни с чем — идём дальше сразу
                Thread.sleep(40L)
            }
            idx += 1
        }
        val hard = raceStart + 13_000L
        while (winner.get() == null && atts.any { it.endAt < 0L } && SystemClock.elapsedRealtime() < hard) {
            Thread.sleep(40L)
        }
        val w = winner.get()
        for (a in atts) {
            if (a !== w) {
                a.cancelled = true
                try {
                    a.conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        if (w != null) {
            w.thread?.join(w.budgetMs + 1500L)
            if (w.thread?.isAlive == true) {
                if (w.error.isEmpty()) w.error = "timeout"
                if (w.endAt < 0) w.endAt = SystemClock.elapsedRealtime()
                try {
                    w.conn?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        return Pair(w, atts)
    }

    fun probe(ctx: Context): ProbeResult {
        val transport = Net.transport(ctx)
        val wallNow = System.currentTimeMillis()
        if (transport == "none") {
            return ProbeResult(Level.OFFLINE, 0L, 0, 0L, "none", wallNow, 0, "", "нет сети", -1L, "offline", "")
        }
        val net = Net.label(ctx, transport)

        val started = SystemClock.elapsedRealtime()
        Live.begin()
        val dl0 = Traffic.take(transport)
        val (chosen, atts) = raceDownloads()
        val dl1 = Traffic.take(transport)
        val traffic = atts.sumOf { it.bytes }
        Prefs.addTraffic(ctx, traffic)
        var lastErr = ""
        for (a in atts) if (a.error.isNotEmpty()) lastErr = a.error
        val duration = (SystemClock.elapsedRealtime() - started).toInt()

        if (chosen == null) {
            Live.phase = Live.DIAG
            Live.progress = 80
            val cause = Diagnose.run(ctx, false, lastErr)
            return ProbeResult(
                Level.NONE, 0L, 0, 0L, transport, wallNow, duration, hostOf(URLS[0]),
                if (lastErr.isEmpty()) "нет ответа" else lastErr, -1L, cause, net
            )
        }

        val host = hostOf(chosen.url)
        val end = if (chosen.endAt > 0) chosen.endAt else SystemClock.elapsedRealtime()
        val latency = (chosen.headersAt - chosen.t0).toInt().coerceAtLeast(0)
        val ownSpeed: Long = if (chosen.bytes < 20_000L) {
            // Мало данных: считаем по всему времени, иначе цифра будет завышена.
            chosen.bytes * 1000L / maxOf(end - chosen.t0, 1L)
        } else {
            chosen.bytes * 1000L / maxOf(end - chosen.headersAt, 50L)
        }
        // Другие приложения тоже качали по этому каналу: канал пропустил и их данные, поэтому прибавляем их скорость.
        val loadDown = Traffic.foreign(dl0, dl1, false)
        val speed = ownSpeed + loadDown
        val downLevel = Level.fromSpeed(speed)
        Live.downBps = speed
        Live.latencyMs = latency

        // Выгрузку проверяем, только если данные вообще идут.
        var upBps = -1L
        var loadUp = 0L
        if (downLevel >= Level.WEAK) {
            Live.phase = Live.UP
            Live.progress = 55
            val up0 = Traffic.take(transport)
            val u = runUpload()
            val up1 = Traffic.take(transport)
            upBps = upSpeed(u)
            if (upBps >= 0L) {
                loadUp = Traffic.foreign(up0, up1, true)
                upBps += loadUp
                Live.upBps = upBps
            }
            Prefs.addTraffic(ctx, u.written)
        }
        val level = if (upBps >= 0L) minOf(downLevel, Level.fromUp(upBps)) else downLevel

        var cause = ""
        if (level <= Level.NONE) {
            Live.phase = Live.DIAG
            Live.progress = 85
            cause = Diagnose.run(ctx, true, lastErr)
        }
        Live.progress = 100
        val total = (SystemClock.elapsedRealtime() - started).toInt()
        return ProbeResult(level, speed, latency, chosen.bytes, transport, wallNow, total, host, "", upBps, cause, net, loadDown, loadUp)
    }
}

/**
 * Чужой трафик во время проверки. Если другое приложение в это время качает или отправляет, наш замер занижен:
 * канал занят не только нами. Считаем, сколько данных прошло по сети помимо нашего приложения, и прибавляем к замеру.
 */
object Traffic {
    class Snap(val ownRx: Long, val ownTx: Long, val netRx: Long, val netTx: Long, val at: Long)

    // Фоновый шум системы (синхронизация, пинги) не считаем «другим приложением».
    private const val NOISE_BPS = 15_000L
    private const val MIN_WINDOW_MS = 300L

    fun take(transport: String): Snap? {
        // При VPN байты считаются дважды (туннель и сеть) — поправку не делаем.
        if (transport == "vpn" || transport == "none") return null
        return try {
            val uid = android.os.Process.myUid()
            val ownRx = android.net.TrafficStats.getUidRxBytes(uid)
            val ownTx = android.net.TrafficStats.getUidTxBytes(uid)
            val totRx = android.net.TrafficStats.getTotalRxBytes()
            val totTx = android.net.TrafficStats.getTotalTxBytes()
            val mobRx = android.net.TrafficStats.getMobileRxBytes()
            val mobTx = android.net.TrafficStats.getMobileTxBytes()
            if (ownRx < 0L || ownTx < 0L || totRx < 0L || totTx < 0L) return null
            val haveMob = mobRx >= 0L && mobTx >= 0L
            val rx: Long
            val tx: Long
            when {
                transport == "cell" && haveMob -> {
                    rx = mobRx
                    tx = mobTx
                }
                (transport == "wifi" || transport == "eth") && haveMob -> {
                    rx = totRx - mobRx
                    tx = totTx - mobTx
                }
                else -> {
                    rx = totRx
                    tx = totTx
                }
            }
            Snap(ownRx, ownTx, rx, tx, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            null
        }
    }

    /** Скорость чужого трафика между двумя замерами, байт/с; 0 — почти нет или определить не удалось. */
    fun foreign(a: Snap?, b: Snap?, up: Boolean): Long {
        if (a == null || b == null) return 0L
        val ms = b.at - a.at
        if (ms < MIN_WINDOW_MS) return 0L
        val net = if (up) b.netTx - a.netTx else b.netRx - a.netRx
        val own = if (up) b.ownTx - a.ownTx else b.ownRx - a.ownRx
        if (net < 0L || own < 0L) return 0L // счётчики сбросились
        val bps = (net - own).coerceAtLeast(0L) * 1000L / ms
        return if (bps < NOISE_BPS) 0L else bps
    }
}

/** Выясняет, ПОЧЕМУ интернета нет: портал, DNS, нет выхода, ограничение скорости. */
object Diagnose {
    fun run(ctx: Context, gotBytes: Boolean, lastErr: String): String {
        if (Net.captive(ctx)) return "captive"
        if (gotBytes) return "throttle"
        val dnsOk = AtomicBoolean(false)
        val ipOk = AtomicBoolean(false)
        val t1 = Thread {
            try {
                InetAddress.getByName("cloudflare.com")
                dnsOk.set(true)
            } catch (e: Exception) {
                dnsOk.set(false)
            }
        }
        val t2 = Thread { ipOk.set(tcp("1.1.1.1") || tcp("8.8.8.8")) }
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
        t1.join(2500L)
        t2.join(3500L)
        return when {
            !ipOk.get() && !dnsOk.get() -> "nolink"
            ipOk.get() && !dnsOk.get() -> "dns"
            lastErr.contains("imeout") -> "throttle"
            else -> "blocked"
        }
    }

    private fun tcp(host: String): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(host, 443), 1500)
            true
        }
    } catch (e: Exception) {
        false
    }
}

/** Гарантирует, что одновременно идёт только одна проверка, и записывает результат. */
object Runner {
    private val busy = AtomicBoolean(false)

    fun isBusy(): Boolean = busy.get()

    /** Выполняет проверку в текущем потоке. Возвращает null, если проверка уже идёт. */
    fun checkOnce(ctx: Context): ProbeResult? {
        if (!busy.compareAndSet(false, true)) return null
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "internetcheck:probe")
        try {
            wl.acquire(45_000L)
            Live.begin()
            val r = Prober.probe(ctx.applicationContext)
            Prefs.addHistory(ctx.applicationContext, r)
            Store.addSample(ctx.applicationContext, r)
            return r
        } finally {
            Live.end()
            try {
                if (wl.isHeld) wl.release()
            } catch (e: Exception) {
                // ignore
            }
            busy.set(false)
        }
    }

    /**
     * Лёгкая проверка «ответил ли хоть кто-то» (около 1 КБ). Возвращает null, если идёт другая проверка.
     * Нужна, чтобы почти мгновенно заметить появление интернета, когда его нет.
     */
    fun pingOnce(ctx: Context): Boolean? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            return Quick.ping(ctx.applicationContext)
        } finally {
            busy.set(false)
        }
    }
}

object Quick {
    private val TARGETS = listOf(
        "https://cp.cloudflare.com/generate_204" to 204,
        "https://www.gstatic.com/generate_204" to 204,
        "https://speed.cloudflare.com/__down?bytes=1000" to 200
    )
    private const val APPROX_BYTES = 1500L

    /** Все серверы опрашиваются одновременно: хватит одного ответа. */
    fun ping(ctx: Context): Boolean {
        if (Net.transport(ctx) == "none") return false
        Live.quickCount = Live.quickCount + 1
        Live.quickAt = System.currentTimeMillis()
        Prefs.addTraffic(ctx, APPROX_BYTES)
        val any = AtomicBoolean(false)
        val done = AtomicInteger(0)
        for ((url, code) in TARGETS) {
            val th = Thread {
                if (tryOne(url, code)) any.set(true)
                done.incrementAndGet()
            }
            th.isDaemon = true
            th.start()
        }
        val deadline = SystemClock.elapsedRealtime() + 3800L
        while (!any.get() && done.get() < TARGETS.size && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(30L)
        }
        return any.get()
    }

    private fun tryOne(url: String, expect: Int): Boolean {
        val ok = AtomicBoolean(false)
        val holder = arrayOfNulls<HttpURLConnection>(1)
        val th = Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                holder[0] = c
                c.connectTimeout = 2500
                c.readTimeout = 2500
                c.useCaches = false
                c.instanceFollowRedirects = false
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Connection", "close")
                val code = c.responseCode
                if (code == expect) {
                    ok.set(if (expect == 204) true else c.inputStream.read() >= 0)
                }
            } catch (e: Exception) {
                ok.set(false)
            } finally {
                try {
                    holder[0]?.disconnect()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        th.isDaemon = true
        th.start()
        th.join(3500L) // страховка от зависшего DNS
        if (th.isAlive) {
            try {
                holder[0]?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
            return false
        }
        return ok.get()
    }
}
