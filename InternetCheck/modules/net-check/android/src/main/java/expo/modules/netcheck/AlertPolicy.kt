package expo.modules.netcheck

/** Чистые правила оповещений: используются службой и регрессионными тестами. */
object AlertPolicy {
    /** Canonical representation used both for channel identity and URI lookup. */
    fun soundSetting(value: String): String = value.trim().let {
        if (it.equals("silent", ignoreCase = true)) "silent" else it
    }

    fun quietNow(enabled: Boolean, from: Int, to: Int, minute: Int): Boolean = when {
        !enabled || from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    fun channelKey(soundUri: String, loud: Boolean, vibrate: Boolean, up: Boolean): String {
        // Length-prefix the value: a raw delimiter-based key can collide when a
        // custom URI itself contains '|'.  The digest is part of the public
        // channel identity, so changing this also guarantees a fresh channel
        // after changing any sound setting.
        val sound = soundSetting(soundUri)
        val key = "sound=${sound.length}:$sound;loud=$loud;vibrate=$vibrate"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return (if (up) "alert_v2_" else "lost_v2_") + digest
    }

    fun repeatAllowed(alerts: Boolean, repeat: Boolean, quiet: Boolean, usable: Boolean): Boolean =
        alerts && repeat && !quiet && usable

    /**
     * Пока в уведомлении показана кнопка «Принял», его нельзя перерисовывать каждую секунду:
     * система заново строит кнопки, и нажатие, попавшее на момент обновления, теряется
     * (кнопка «не срабатывает с первого раза»). Плановые обновления по таймеру в это время
     * делаются не чаще, чем раз в minGapMs; события (результат проверки, подтверждение) не ограничиваются.
     */
    fun skipTickRefresh(ackPending: Boolean, now: Long, lastPostAt: Long, minGapMs: Long): Boolean =
        ackPending && lastPostAt > 0L && now >= lastPostAt && now - lastPostAt < minGapMs
}
