package com.freeturn.app.domain.subscription

import android.util.Base64
import com.freeturn.app.data.server.SubscriptionNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libXray.LibXray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Тянет тело Xray-подписки (3x-ui и т.п.: base64 с построчными share-ссылками
 * vless://... и т.п.), парсит каждую строку через уже готовый метод libXray
 * convertShareLinksToXrayJson (не пишем свой парсер share-URI) и отдаёт список
 * нод со стабильным ключом идентичности - для diff при повторном обновлении
 * подписки (см. AppPreferences.syncSubscriptionServers).
 */
class XraySubscriptionFetcher {

    suspend fun fetch(url: String): List<SubscriptionNode> = withContext(Dispatchers.IO) {
        decodeLines(fetchBody(url)).mapNotNull(::convertLine)
    }

    private fun fetchBody(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    // Подписка обычно - base64 (может быть url-safe, без padding) с построчными
    // share-ссылками. Некоторые панели отдают ссылки прямым текстом - если тело
    // не похоже на валидный base64 (после декодирования нет "://"), используем как есть.
    private fun decodeLines(body: String): List<String> {
        val trimmed = body.trim()
        val decoded = runCatching {
            val normalized = trimmed.replace('-', '+').replace('_', '/').replace("\\s".toRegex(), "")
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()

        val text = if (decoded != null && decoded.contains("://")) decoded else trimmed
        return text.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun convertLine(line: String): SubscriptionNode? {
        val request = JSONObject().apply {
            put("apiVersion", 1)
            put("method", "convertShareLinksToXrayJson")
            put("payload", JSONObject().put("text", line))
        }
        val response = runCatching { JSONObject(LibXray.invoke(request.toString())) }.getOrNull() ?: return null
        if (!response.optBoolean("success", false)) return null
        val xrayConfig = response.optString("data").takeIf { it.isNotBlank() } ?: return null

        return SubscriptionNode(key = stableKey(line), name = remark(line), xrayConfig = sanitize(xrayConfig))
    }

    // libXray v26.7.28 (см. память android-reality-libxray-2026-08-10.md - тот же
    // релиз, что и в RealityVpnService) кладёт remark share-ссылки в outbound.sendThrough
    // (живая проверка: конвертация "vless://...#vps1ru-direct-My_phone" дала
    // outbounds[0].sendThrough == "vps1ru-direct-My_phone") - поле, которое xray-core
    // обязан распарсить как локальный bind-адрес, отчего runXray падает с
    // "unable to send through: <remark>".
    private fun sanitize(xrayConfig: String): String {
        val root = runCatching { JSONObject(xrayConfig) }.getOrNull() ?: return xrayConfig
        root.optJSONArray("outbounds")?.let { outbounds ->
            for (i in 0 until outbounds.length()) {
                outbounds.optJSONObject(i)?.remove("sendThrough")
            }
        }
        // Конвертер систематически расставляет явные JSON null по всем необязательным
        // полям (transport/env/log/routing/dns/reverse/vnext/target/dest и т.д. - живой
        // дамп содержал их десятками). Для большинства Go-полей (указатели/слайсы/карты)
        // это безвредно - unmarshal нуля даёт nil, как и для отсутствующего ключа. НО
        // realitySettings.target/dest - json.RawMessage: explicit JSON null unmarshal'ится
        // в НЕ-nil RawMessage("null") (4 байта), а не в nil-слайс. infra/conf/
        // transport_security.go:62 проверяет именно "c.Dest != nil" - с этим полем это
        // ложно-положительно уводит клиентский REALITY-конфиг в СЕРВЕРНУЮ ветку парсинга
        // (требует serverNames/shortIds во множественном числе вместо serverName/shortId),
        // отсюда "empty serverNames" на клиентском конфиге. Вместо точечного патча одного
        // поля - общий рекурсивный проход: явный null ключа неотличим по семантике от
        // отсутствия ключа почти везде, вычищаем такие ключи по всему дереву целиком.
        stripExplicitNulls(root)
        return root.toString()
    }

    private fun stripExplicitNulls(value: Any?) {
        when (value) {
            is JSONObject -> {
                val nullKeys = value.keys().asSequence().filter { value.isNull(it) }.toList()
                nullKeys.forEach { value.remove(it) }
                value.keys().asSequence().toList().forEach { stripExplicitNulls(value.opt(it)) }
            }
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) stripExplicitNulls(value.opt(i))
            }
        }
    }

    // Идентичность ноды - вся ссылка БЕЗ fragment (remark меняется в подписке
    // свободно, это не новая нода), не сам xrayConfig - в нём могут отличаться
    // несущественные поля между вызовами.
    private fun stableKey(line: String): String {
        val withoutFragment = line.substringBefore('#')
        val digest = MessageDigest.getInstance("SHA-256").digest(withoutFragment.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun remark(line: String): String {
        val fragment = line.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isBlank()) return "Нода"
        return runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
    }
}
