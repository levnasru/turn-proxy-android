package com.freeturn.app.service.reality

import android.os.Bundle

/**
 * IPC-протокол между RealityVpnService (процесс :reality) и основным
 * процессом через Messenger. Старт/стоп сервиса идёт отдельно, через
 * Intent (см. AndroidProxyServiceLauncher) - здесь только поток статуса
 * в обратную сторону, :reality -> основной процесс.
 */
object RealityIpc {
    /** Клиент -> сервис: регистрация, Message.replyTo несёт обратный Messenger. */
    const val MSG_REGISTER_CLIENT = 1
    /** Сервис -> клиент: полный снепшот состояния (и на регистрацию, и на каждое изменение). */
    const val MSG_STATE_UPDATE = 2
    /** Сервис -> клиент: одна строка лога. */
    const val MSG_LOG_LINE = 3
}

data class RealityState(
    val running: Boolean,
    val active: Int,
    val total: Int,
    val failedMessage: String?,
    val tunnelActive: Boolean,
    val connectedSince: Long?,
    val teardownComplete: Boolean
)

private const val KEY_RUNNING = "running"
private const val KEY_ACTIVE = "active"
private const val KEY_TOTAL = "total"
private const val KEY_FAILED_MESSAGE = "failedMessage"
private const val KEY_TUNNEL_ACTIVE = "tunnelActive"
private const val KEY_CONNECTED_SINCE = "connectedSince"
private const val KEY_TEARDOWN_COMPLETE = "teardownComplete"
private const val KEY_LOG_TEXT = "logText"

fun RealityState.toBundle(): Bundle = Bundle().apply {
    putBoolean(KEY_RUNNING, running)
    putInt(KEY_ACTIVE, active)
    putInt(KEY_TOTAL, total)
    putString(KEY_FAILED_MESSAGE, failedMessage)
    putBoolean(KEY_TUNNEL_ACTIVE, tunnelActive)
    connectedSince?.let { putLong(KEY_CONNECTED_SINCE, it) }
    putBoolean(KEY_TEARDOWN_COMPLETE, teardownComplete)
}

fun Bundle.toRealityState(): RealityState = RealityState(
    running = getBoolean(KEY_RUNNING),
    active = getInt(KEY_ACTIVE),
    total = getInt(KEY_TOTAL),
    failedMessage = getString(KEY_FAILED_MESSAGE),
    tunnelActive = getBoolean(KEY_TUNNEL_ACTIVE),
    connectedSince = if (containsKey(KEY_CONNECTED_SINCE)) getLong(KEY_CONNECTED_SINCE) else null,
    teardownComplete = getBoolean(KEY_TEARDOWN_COMPLETE)
)

fun realityLogBundle(text: String): Bundle = Bundle().apply { putString(KEY_LOG_TEXT, text) }

fun Bundle.toRealityLogText(): String = getString(KEY_LOG_TEXT).orEmpty()
