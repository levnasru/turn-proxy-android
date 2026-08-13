package com.freeturn.app.data.server

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Имена JSON-ключей - контракт с сохранёнными данными: менять только с миграцией.
internal object SubscriptionJson {
    fun encodeList(list: List<Subscription>): String {
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun decodeList(raw: String?): List<Subscription> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encode(s: Subscription): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("url", s.url)
    }

    private fun decode(o: JSONObject): Subscription = Subscription(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        name = o.optString("name").ifBlank { Subscription.FALLBACK_NAME },
        url = o.optString("url")
    )
}
