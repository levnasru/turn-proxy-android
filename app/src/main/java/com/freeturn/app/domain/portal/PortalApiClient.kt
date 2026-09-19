package com.freeturn.app.domain.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** То же, что возвращает vkturn-ios-portal's /api/v1/config?device=android. */
data class PortalConfig(
    val hubUrls: List<String>,
    val hubPin: String,
    val hubToken: String,
    val peer: String,
    val obfProfile: String,
    val obfKey: String,
    val streams: Int,
    val wgConfig: String,
    val xraySubscriptionUrl: String? = null,
    val splitMode: String? = null
)

sealed interface ConfigFetchResult {
    data class Success(val config: PortalConfig, val etag: String?) : ConfigFetchResult
    data object NotModified : ConfigFetchResult
    data class Unauthorized(val message: String) : ConfigFetchResult
    data class Error(val message: String) : ConfigFetchResult
}

/**
 * Self-service портал (vkturn-ios-portal, lft.levnas.ru) - те же /api/v1/login +
 * /api/v1/config, что уже использует vkturn-desktop (cmd/desktop/apiclient.go в
 * free-turn-proxy). ?device=android читает android_accounts/android_streams
 * пользователя на портале вместо desktop_accounts/desktop_streams, JSON-контракт
 * ответа тот же (DesktopConfig на стороне портала).
 */
class PortalApiClient(private val baseUrl: String = DEFAULT_BASE_URL) {

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", username).put("password", password).toString()
        val conn = postJson("$baseUrl/api/v1/login", body, bearer = null)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("login: HTTP ${conn.responseCode}")
            }
            val token = JSONObject(conn.inputStream.bufferedReader().readText()).optString("token")
            if (token.isBlank()) throw IOException("login: пустой token в ответе")
            token
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchConfigWithEtag(token: String, etag: String? = null): ConfigFetchResult = withContext(Dispatchers.IO) {
        val conn = (URL("$baseUrl/api/v1/config?device=android").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Authorization", "Bearer $token")
            if (!etag.isNullOrBlank()) {
                setRequestProperty("If-None-Match", etag)
            }
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return@withContext ConfigFetchResult.NotModified
            }
            if (conn.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                return@withContext ConfigFetchResult.Unauthorized("token expired or invalid")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext ConfigFetchResult.Error("на портале для этого логина не настроен доступ для Android")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext ConfigFetchResult.Error("config: HTTP ${conn.responseCode}")
            }
            val newEtag = conn.getHeaderField("ETag")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val urls = json.optJSONArray("hubUrls")
            val cfg = PortalConfig(
                hubUrls = urls?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty(),
                hubPin = json.optString("hubPin"),
                hubToken = json.optString("hubToken"),
                peer = json.optString("peer"),
                obfProfile = json.optString("obfProfile"),
                obfKey = json.optString("obfKey"),
                streams = json.optInt("streams", DEFAULT_STREAMS),
                wgConfig = json.optString("wgConfig"),
                xraySubscriptionUrl = json.optString("xraySubscriptionUrl").takeIf { it.isNotBlank() },
                splitMode = json.optString("splitMode").takeIf { it.isNotBlank() }
            )
            ConfigFetchResult.Success(cfg, newEtag)
        } catch (e: Exception) {
            ConfigFetchResult.Error(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetchConfig(token: String): PortalConfig = withContext(Dispatchers.IO) {
        when (val res = fetchConfigWithEtag(token, null)) {
            is ConfigFetchResult.Success -> res.config
            is ConfigFetchResult.Error -> throw IOException(res.message)
            is ConfigFetchResult.Unauthorized -> throw IOException(res.message)
            is ConfigFetchResult.NotModified -> throw IOException("unexpected 304 without etag")
        }
    }

    private fun postJson(url: String, body: String, bearer: String?): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        return conn
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://lft.levnas.ru"
        private const val DEFAULT_STREAMS = 10
        private const val TIMEOUT_MS = 20_000
    }
}
