package com.freeturn.app.data.server

import java.util.UUID

/**
 * Именованная Xray-подписка (3x-ui и т.п.): URL, по которому периодически
 * подтягивается список нод. Сами ноды - обычные [Server] с [Server.subscriptionId],
 * равным [id] этой подписки; сама подписка узлов не хранит.
 */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String
) {
    companion object {
        const val FALLBACK_NAME = "Подписка"
    }
}

data class SubscriptionSyncResult(val added: Int, val updated: Int, val removed: Int)

/** Одна распарсенная нода из тела подписки - результат [share.ConvertShareLinksToXrayJson]. */
data class SubscriptionNode(
    /** Стабильная идентичность ноды (адрес+uuid+порт) - НЕ имя, оно может смениться в подписке. */
    val key: String,
    val name: String,
    val xrayConfig: String
)
