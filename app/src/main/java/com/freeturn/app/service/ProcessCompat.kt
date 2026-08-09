package com.freeturn.app.service

import android.os.Build
import java.util.concurrent.TimeUnit

internal fun Process.destroyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}

/**
 * Гасит ядро мягко: SIGTERM -> ядро закрывает TURN-стримы (Refresh lifetime=0) и релей
 * отпускает аллокацию сразу, а не через её TTL. destroy()/destroyForcibly() на Android
 * оба шлют SIGKILL, поэтому сигнал отправляем сами. Не успел за timeoutMs - добиваем.
 * ponytail: на API<26 нет Process.pid(), там остаётся старое поведение (SIGKILL).
 */
internal fun Process.stopGracefully(timeoutMs: Long) {
    val pid = childPid()
    if (pid != null) {
        try {
            android.os.Process.sendSignal(pid, SIGTERM)
            if (waitForCompat(timeoutMs, TimeUnit.MILLISECONDS)) return
        } catch (_: Exception) {
            // Процесс уже мёртв или сигнал не прошёл - добиваем ниже.
        }
    }
    destroyCompat()
}

/**
 * В android.jar нет Process.pid() (это Java 9 API), поэтому достаём приватное
 * поле реализации рефлексией. Не получилось - вызывающий шлёт SIGKILL как раньше.
 */
private fun Process.childPid(): Int? = try {
    javaClass.getDeclaredField("pid").let {
        it.isAccessible = true
        (it.get(this) as? Number)?.toInt()
    }
} catch (_: Exception) {
    null
}

private const val SIGTERM = 15

internal fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
    while (System.currentTimeMillis() < deadline) {
        try { exitValue(); return true } catch (_: IllegalThreadStateException) { Thread.sleep(100) }
    }
    return try { exitValue(); true } catch (_: IllegalThreadStateException) { false }
}
