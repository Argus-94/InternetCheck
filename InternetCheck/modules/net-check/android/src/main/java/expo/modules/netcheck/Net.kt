package expo.modules.netcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager

object Net {
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
    fun usable(ctx: Context, r: ProbeResult): Boolean {
        if (r.level <= Level.NONE) return false
        if (Prefs.onlyWifi(ctx) && r.transport != "wifi" && r.transport != "eth") return false
        return when (Prefs.targetMode(ctx)) {
            1 -> r.level >= Level.GOOD
            2 -> r.speedBps >= Prefs.targetDownKb(ctx) * 1000L &&
                (!Prefs.upCounts(ctx) || r.upBps < 0L || r.upBps >= Prefs.targetUpKb(ctx) * 1000L)
            else -> Level.usable(r.level)
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
