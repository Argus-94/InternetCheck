package expo.modules.netcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager

object Net {
    /** Только текущее состояние подключения ОС, не результат предыдущего замера. */
    fun connectionStatus(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) {
            "Нет сети"
        } else {
            val name = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Моб. сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Сеть"
            }
            when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> "$name: вход"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> name
                else -> "$name: без интернета"
            }
        }
    } catch (_: Exception) {
        "Сеть: ?"
    }

    /** "none" | "wifi" | "cell" | "eth" | "vpn" | "other" */
    fun transport(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return "none"
            val c = cm.getNetworkCapabilities(n) ?: return "none"
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "none"
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (e: Exception) {
            "other"
        }
    }

    /** Система считает, что сеть требует вход через страницу авторизации. */
    fun captive(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        } catch (e: Exception) {
            false
        }
    }

    /** Название Wi‑Fi (нужно разрешение на местоположение) или оператора; иначе общее название типа сети. */
    fun label(ctx: Context, transport: String): String {
        return when (transport) {
            "wifi" -> wifiName(ctx) ?: "Wi‑Fi"
            "cell" -> operatorName(ctx) ?: "Мобильная сеть"
            "eth" -> "Ethernet"
            "vpn" -> "VPN"
            "other" -> "Сеть"
            else -> ""
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiName(ctx: Context): String? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wm.connectionInfo?.ssid ?: return null
            val s = raw.trim().trim('"')
            if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") null else s
        } catch (e: Exception) {
            null
        }
    }

    private fun operatorName(ctx: Context): String? {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val n = tm.networkOperatorName
            if (n.isNullOrBlank()) null else n.trim()
        } catch (e: Exception) {
            null
        }
    }
}

/** Решает, считается ли результат «нормальным интернетом» по настройкам пользователя. */
object Target {
    fun usable(ctx: Context, r: ProbeResult): Boolean = accepts(
        r, Prefs.targetMode(ctx), Prefs.targetDownKb(ctx), Prefs.targetUpKb(ctx),
        Prefs.onlyWifi(ctx), Prefs.upCounts(ctx)
    )

    fun accepts(r: ProbeResult, mode: Int, downKb: Int, upKb: Int, onlyWifi: Boolean, upCounts: Boolean): Boolean {
        if (r.level <= Level.OFFLINE || r.speedBps < Level.NONE_BELOW) return false
        if (onlyWifi && r.transport != "wifi" && r.transport != "eth") return false
        val level = Level.combine(Level.fromSpeed(r.speedBps), r.upBps, upCounts)
        return when (mode) {
            1 -> level >= Level.GOOD
            2 -> r.speedBps >= downKb * 1000L &&
                (!upCounts || upKb == 0 || r.upBps >= upKb * 1000L)
            else -> Level.usable(level)
        }
    }
}

/** Состояние батареи: при низком заряде без зарядки проверки делаем реже и без лишних замеров. */
object Power {
    fun lowBattery(ctx: Context): Boolean {
        if (!Prefs.saveBattery(ctx)) return false
        return try {
            val i = ctx.applicationContext.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
            val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (level < 0 || scale <= 0) false else (level * 100 / scale) <= 15 && !charging
        } catch (e: Exception) {
            false
        }
    }
}
