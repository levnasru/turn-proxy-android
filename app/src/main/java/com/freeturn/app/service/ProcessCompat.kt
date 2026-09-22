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
 * В android.jar нет прямого Process.pid() (Java 9 API), а прямой доступ к приватному
 * полю "pid" блокируется Hidden API Enforcement на API 28+.
 * Извлекаем PID через метод pid(), toString() regex и fallback на рефлексию поля.
 */
private fun Process.childPid(): Int? {
    try {
        val method = javaClass.getMethod("pid")
        val res = (method.invoke(this) as? Number)?.toInt()
        if (res != null && res > 0) return res
    } catch (_: Throwable) {}

    try {
        val match = Regex("""(?:\[pid=|\bpid=)(\d+)""").find(toString())
        if (match != null) {
            val res = match.groupValues[1].toIntOrNull()
            if (res != null && res > 0) return res
        }
    } catch (_: Throwable) {}

    return try {
        javaClass.getDeclaredField("pid").let {
            it.isAccessible = true
            (it.get(this) as? Number)?.toInt()
        }
    } catch (_: Throwable) {
        null
    }
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
