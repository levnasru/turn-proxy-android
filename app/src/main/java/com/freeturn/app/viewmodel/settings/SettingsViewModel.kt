package com.freeturn.app.viewmodel.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.backup.BackupCrypto
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerOpts
import com.freeturn.app.data.server.ServersSnapshot
import com.freeturn.app.data.server.Subscription
import com.freeturn.app.domain.subscription.XraySubscriptionFetcher
import com.freeturn.app.domain.portal.PortalApiClient
import com.freeturn.app.domain.portal.ConfigFetchResult
import com.freeturn.app.domain.backup.BackupManager
import com.freeturn.app.domain.update.AppUpdater
import com.freeturn.app.domain.proxy.LocalProxyManager
import com.freeturn.app.domain.proxy.ProxyOrchestrator
import com.freeturn.app.domain.UpdateState
import com.freeturn.app.domain.proxy.ProxyServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface SubscriptionSyncState {
    data object Idle : SubscriptionSyncState
    data object Running : SubscriptionSyncState
    data class Done(val added: Int, val updated: Int, val removed: Int) : SubscriptionSyncState
    data class Error(val message: String) : SubscriptionSyncState
}

sealed interface PortalLoginState {
    data object Idle : PortalLoginState
    data object Running : PortalLoginState
    data class Done(val serverName: String) : PortalLoginState
    data class Error(val message: String) : PortalLoginState
}

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager,
    private val appUpdater: AppUpdater,
    private val orchestrator: ProxyOrchestrator,
    private val backupManager: BackupManager,
    private val subscriptionFetcher: XraySubscriptionFetcher,
    private val portalApi: PortalApiClient,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val proxyListen: StateFlow<String> = prefs.proxyListenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "0.0.0.0:56000")

    val proxyConnect: StateFlow<String> = prefs.proxyConnectFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "127.0.0.1:40537")

    val dynamicTheme: StateFlow<Boolean> = prefs.dynamicThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val nerdMode: StateFlow<Boolean> = prefs.nerdModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hotspotProxyEnabled: StateFlow<Boolean> = prefs.hotspotProxyEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val serversSnapshot: StateFlow<ServersSnapshot> = prefs.serversSnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersSnapshot())

    val subscriptions: StateFlow<List<Subscription>> = prefs.subscriptionsSnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _subscriptionSyncState = MutableStateFlow<SubscriptionSyncState>(SubscriptionSyncState.Idle)
    val subscriptionSyncState: StateFlow<SubscriptionSyncState> = _subscriptionSyncState.asStateFlow()

    private val _portalLoginState = MutableStateFlow<PortalLoginState>(PortalLoginState.Idle)
    val portalLoginState: StateFlow<PortalLoginState> = _portalLoginState.asStateFlow()

    val updateState: StateFlow<UpdateState> = appUpdater.state

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initialTgSubscribeShown = MutableStateFlow(false)
    val initialTgSubscribeShown: StateFlow<Boolean> = _initialTgSubscribeShown.asStateFlow()

    // Снимок не даёт диалогу мигнуть на дефолтном значении до первого emit.
    private val _initialSuppressTgPrompt = MutableStateFlow(false)
    val initialSuppressTgPrompt: StateFlow<Boolean> = _initialSuppressTgPrompt.asStateFlow()

    val suppressUpdatePrompt: StateFlow<Boolean> = prefs.suppressUpdatePromptFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val suppressTgPrompt: StateFlow<Boolean> = prefs.suppressTgPromptFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val privacyMode: StateFlow<Boolean> = prefs.privacyModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Ожидаем DataStore, чтобы дефолт StateFlow не пропустил диалог первой сессии.
    suspend fun batteryPromptShownOnce(): Boolean = prefs.batteryPromptShownFlow.first()

    init {
        viewModelScope.launch {
            _initialTgSubscribeShown.value = prefs.tgSubscribeShownFlow.first()
            _initialSuppressTgPrompt.value = prefs.suppressTgPromptFlow.first()
            ProxyServiceState.setLogsEnabled(prefs.clientConfigFlow.first().logsEnabled)
            _isInitialized.value = true
        }
        viewModelScope.launch {
            appUpdater.checkForUpdate(silent = true)
        }
    }

    fun setPrivacyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setPrivacyMode(enabled) }
    }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }

    fun setNerdMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setNerdMode(enabled) }
    }

    fun setHotspotProxyEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setHotspotProxyEnabled(enabled) }
    }

    fun setTgSubscribeShown() {
        viewModelScope.launch { prefs.setTgSubscribeShown() }
    }

    fun setBatteryPromptShown() {
        viewModelScope.launch { prefs.setBatteryPromptShown() }
    }

    fun setSuppressUpdatePrompt(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuppressUpdatePrompt(enabled) }
    }

    fun setSuppressTgPrompt(enabled: Boolean) {
        viewModelScope.launch { prefs.setSuppressTgPrompt(enabled) }
    }

    // expectedActiveId не даёт отложенной записи затереть новый активный сервер.
    fun saveClientConfig(config: ClientConfig, expectedActiveId: String? = null) {
        viewModelScope.launch {
            val targetId = expectedActiveId
                ?: prefs.serversSnapshot.first().activeId ?: return@launch
            if (!prefs.updateServer(targetId) { it.copy(client = config) }) return@launch
            if (targetId == prefs.serversSnapshot.first().activeId) {
                ProxyServiceState.setLogsEnabled(config.logsEnabled)
            }
        }
    }

    fun setSplitTunnelMode(value: String) {
        viewModelScope.launch {
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(splitTunnelMode = value))
            }
        }
    }

    fun setSplitTunnelApps(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(splitTunnelApps = trimmed))
            }
        }
    }

    fun setBypassRules(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(bypassRules = trimmed))
            }
        }
    }

    // Ручной сервер создаётся неактивным и с sync OFF, чтобы его можно было донастроить без SSH.
    fun addManualServer(name: String, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            val server = Server(name = name, client = ClientConfig(syncServerSwitches = false))
            onAdded(prefs.addServer(server))
        }
    }

    /** Создаёт подписку и сразу тянет её ноды - первый импорт списком, не пустышкой. */
    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            _subscriptionSyncState.value = SubscriptionSyncState.Running
            val subscription = Subscription(name = name, url = url)
            val id = prefs.addSubscription(subscription)
            syncSubscription(id, url)
        }
    }

    fun refreshSubscription(id: String) {
        val subscription = subscriptions.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _subscriptionSyncState.value = SubscriptionSyncState.Running
            syncSubscription(id, subscription.url)
        }
    }

    private suspend fun syncSubscription(id: String, url: String) {
        _subscriptionSyncState.value = try {
            val nodes = subscriptionFetcher.fetch(url)
            if (nodes.isEmpty()) {
                SubscriptionSyncState.Error("Подписка не вернула ни одной ноды")
            } else {
                val result = prefs.syncSubscriptionServers(id, nodes)
                SubscriptionSyncState.Done(result.added, result.updated, result.removed)
            }
        } catch (e: Exception) {
            SubscriptionSyncState.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun clearSubscriptionSyncState() {
        _subscriptionSyncState.value = SubscriptionSyncState.Idle
    }

    /**
     * Self-service вход (тот же /api/v1/login + /api/v1/config?device=android
     * на vkturn-ios-portal, что уже использует vkturn-desktop) - заводит НОВЫЙ
     * профиль-сервер из полученных hub-кредов, тем же путём, что ImportViewModel
     * заводит профиль из freeturn://-ссылки (Server(client, opts) + addServer).
     * Не трогает WireGuard - full-tunnel для Android по-прежнему через
     * panel.js/.fabackup (см. User.AndroidAccounts на портале).
     */
    fun loginToPortal(username: String, password: String) {
        viewModelScope.launch {
            _portalLoginState.value = PortalLoginState.Running
            _portalLoginState.value = try {
                val token = portalApi.login(username, password)
                val fetchRes = portalApi.fetchConfigWithEtag(token)
                val (cfg, etag) = when (fetchRes) {
                    is ConfigFetchResult.Success -> fetchRes.config to fetchRes.etag
                    is ConfigFetchResult.Error -> throw IOException(fetchRes.message)
                    is ConfigFetchResult.Unauthorized -> throw IOException(fetchRes.message)
                    is ConfigFetchResult.NotModified -> throw IOException("unexpected 304 on first login")
                }
                prefs.savePortalAuth(username, password, token, etag)
                val wgConf = cfg.wgConfig.trim()
                val hasWg = wgConf.isNotEmpty()
                val server = Server(
                    name = "VK-TURN ($username)",
                    client = ClientConfig(
                        provider = Provider.HUB,
                        serverAddress = cfg.peer,
                        hubUrl = cfg.hubUrls.joinToString(","),
                        hubPin = cfg.hubPin,
                        hubToken = cfg.hubToken,
                        threads = cfg.streams.takeIf { it > 0 } ?: ClientConfig.DEFAULT_THREADS,
                        tcpForward = !hasWg,
                        bond = !hasWg,
                        tunnelTransport = if (hasWg) TunnelTransport.WIREGUARD
                        else TunnelTransport.NONE,
                        wireGuardConfig = wgConf
                    ),
                    opts = ServerOpts(
                        obfProfile = cfg.obfProfile.ifBlank { ObfProfile.NONE },
                        obfKey = cfg.obfKey
                    )
                )
                prefs.addServer(server, activate = true)
                if (!cfg.xraySubscriptionUrl.isNullOrBlank()) {
                    try {
                        syncXraySubscription(cfg.xraySubscriptionUrl, prefs, subscriptionFetcher)
                    } catch (_: Exception) {}
                }
                PortalLoginState.Done(server.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PortalLoginState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Активирует режим Reality:
     * 1. Если уже есть сервер с заполненным xrayConfig - переключается на него.
     * 2. Если нет - подтягивает ноды из подписки (или дефолтной семейной Reality-подписки),
     *    создаёт узел и сразу делает его активным, исключая ошибку "Не задан Xray-конфиг".
     */
    fun switchToReality() {
        viewModelScope.launch {
            val servers = prefs.serversSnapshot.first().list
            val existingReality = servers.firstOrNull {
                it.client.tunnelTransport == TunnelTransport.REALITY && it.client.xrayConfig.isNotBlank()
            } ?: servers.firstOrNull { it.client.xrayConfig.isNotBlank() }

            if (existingReality != null) {
                applyServer(existingReality.id)
                updateServerClient(existingReality.id) {
                    it.copy(tunnelTransport = TunnelTransport.REALITY)
                }
                return@launch
            }

            _subscriptionSyncState.value = SubscriptionSyncState.Running
            try {
                val subs = prefs.subscriptionsSnapshot.first()
                val sub = subs.firstOrNull() ?: run {
                    val defaultUrl = "https://panelproxy.levnas.ru:2096/sub/sk6crmdv007x73p4"
                    val id = prefs.addSubscription(Subscription(name = "Reality", url = defaultUrl))
                    Subscription(id = id, name = "Reality", url = defaultUrl)
                }
                val nodes = subscriptionFetcher.fetch(sub.url)
                if (nodes.isNotEmpty()) {
                    val res = prefs.syncSubscriptionServers(sub.id, nodes)
                    _subscriptionSyncState.value = SubscriptionSyncState.Done(res.added, res.updated, res.removed)
                    val updatedServers = prefs.serversSnapshot.first().list
                    val newReality = updatedServers.firstOrNull { it.subscriptionId == sub.id }
                        ?: updatedServers.firstOrNull { it.client.xrayConfig.isNotBlank() }
                    if (newReality != null) {
                        applyServer(newReality.id)
                    }
                } else {
                    _subscriptionSyncState.value = SubscriptionSyncState.Error("Подписка не вернула узлов")
                }
            } catch (e: Exception) {
                _subscriptionSyncState.value = SubscriptionSyncState.Error(e.message ?: "Ошибка загрузки Reality")
            }
        }
    }

    fun syncPortalConfig() {
        viewModelScope.launch {
            syncPortalConfigSilently(portalApi, prefs, subscriptionFetcher)
        }
    }

    companion object {
        suspend fun syncXraySubscription(
            subUrl: String,
            prefs: AppPreferences,
            subscriptionFetcher: XraySubscriptionFetcher
        ) {
            val existingSubs = prefs.subscriptionsSnapshot.first()
            val existing = existingSubs.firstOrNull { it.url == subUrl }
            val subId = existing?.id ?: prefs.addSubscription(
                Subscription(name = "Portal Reality", url = subUrl)
            )
            val nodes = subscriptionFetcher.fetch(subUrl)
            if (nodes.isNotEmpty()) {
                prefs.syncSubscriptionServers(subId, nodes)
            }
        }

        suspend fun syncPortalConfigSilently(
            portalApi: PortalApiClient,
            prefs: AppPreferences,
            subscriptionFetcher: XraySubscriptionFetcher
        ) {
            var token = prefs.portalTokenFlow.first() ?: return
            val etag = prefs.portalEtagFlow.first()
            var fetchRes = portalApi.fetchConfigWithEtag(token, etag)
            if (fetchRes is ConfigFetchResult.Unauthorized) {
                val user = prefs.portalUsernameFlow.first()
                val pass = prefs.portalPasswordFlow.first()
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    try {
                        token = portalApi.login(user, pass)
                        prefs.updatePortalToken(token)
                        fetchRes = portalApi.fetchConfigWithEtag(token, etag)
                    } catch (_: Exception) {
                        return
                    }
                } else {
                    return
                }
            }
            if (fetchRes is ConfigFetchResult.Success) {
                val cfg = fetchRes.config
                fetchRes.etag?.let { prefs.updatePortalEtag(it) }
                val wgConf = cfg.wgConfig.trim()
                val hasWg = wgConf.isNotEmpty()
                prefs.updateActiveServer { srv ->
                    srv.copy(
                        client = srv.client.copy(
                            serverAddress = cfg.peer,
                            hubUrl = cfg.hubUrls.joinToString(","),
                            hubPin = cfg.hubPin,
                            hubToken = cfg.hubToken,
                            threads = cfg.streams.takeIf { it > 0 } ?: srv.client.threads,
                            wireGuardConfig = if (hasWg) wgConf else srv.client.wireGuardConfig
                        ),
                        opts = srv.opts.copy(
                            obfProfile = cfg.obfProfile.ifBlank { srv.opts.obfProfile },
                            obfKey = cfg.obfKey.ifBlank { srv.opts.obfKey }
                        )
                    )
                }
                if (!cfg.xraySubscriptionUrl.isNullOrBlank()) {
                    try {
                        syncXraySubscription(cfg.xraySubscriptionUrl, prefs, subscriptionFetcher)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun clearPortalLoginState() {
        _portalLoginState.value = PortalLoginState.Idle
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch { prefs.deleteSubscription(id) }
    }

    fun renameServer(id: String, name: String) {
        viewModelScope.launch { prefs.renameServer(id, name) }
    }

    fun cloneServer(id: String, onCloned: (String) -> Unit) {
        viewModelScope.launch { prefs.cloneServer(id)?.let(onCloned) }
    }

    fun applyServer(id: String) {
        viewModelScope.launch {
            val target = prefs.serversSnapshot.first().list.firstOrNull { it.id == id }
                ?: return@launch
            prefs.setActiveServerId(target.id)
            orchestrator.restartProxyIfRunning()
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { prefs.deleteServer(id) }
    }

    fun updateServerClient(id: String, transform: (ClientConfig) -> ClientConfig) {
        viewModelScope.launch {
            if (!prefs.updateServer(id) { it.copy(client = transform(it.client)) }) return@launch
            val snap = prefs.serversSnapshot.first()
            snap.active?.takeIf { it.id == id }?.let {
                ProxyServiceState.setLogsEnabled(it.client.logsEnabled)
            }
        }
    }

    fun updateServerOpts(id: String, transform: (ServerOpts) -> ServerOpts) {
        viewModelScope.launch {
            prefs.updateServer(id) { it.copy(opts = transform(it.opts)) }
        }
    }

    fun setBond(enabled: Boolean) {
        viewModelScope.launch {
            val changed = prefs.updateActiveServer {
                it.copy(client = it.client.copy(bond = enabled))
            }
            if (changed) orchestrator.restartProxyIfRunning()
        }
    }

    fun setActiveVkLink(link: String) {
        viewModelScope.launch {
            prefs.updateActiveServer {
                it.copy(client = it.client.copy(vkLink = link.trim()))
            }
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch { appUpdater.checkForUpdate(silent = false) }
    }

    fun downloadUpdate() {
        viewModelScope.launch { appUpdater.downloadUpdate() }
    }

    fun installUpdate() {
        appUpdater.installUpdate()
    }

    fun resetUpdateState() {
        appUpdater.resetState()
    }

    // Буфер сохраняет событие, пока экран не подписан.
    private val _backupEvents = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 1)
    val backupEvents: SharedFlow<BackupEvent> = _backupEvents.asSharedFlow()

    fun exportBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            val event = try {
                val bytes = backupManager.export(password)
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("no output stream")
                }
                BackupEvent.ExportSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupEvent.ExportFailed
            }
            _backupEvents.emit(event)
        }
    }

    fun restoreBackup(uri: Uri, password: String) {
        viewModelScope.launch {
            val event = try {
                val bytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IOException("no input stream")
                }
                // Разбор до остановки рантайма: неверный пароль не должен гасить подключение.
                val data = backupManager.decode(bytes, password)
                if (ProxyServiceState.isRunning.value) proxyManager.stopProxy()
                val count = backupManager.restore(data)
                proxyManager.clearState()
                ProxyServiceState.clearLogs()
                BackupEvent.RestoreSuccess(count)
            } catch (e: CancellationException) {
                throw e
            } catch (_: BackupCrypto.BadPasswordException) {
                BackupEvent.RestoreFailed(RestoreFailReason.BAD_PASSWORD)
            } catch (_: BackupCrypto.FormatException) {
                BackupEvent.RestoreFailed(RestoreFailReason.BAD_FILE)
            } catch (_: Exception) {
                BackupEvent.RestoreFailed(RestoreFailReason.IO)
            }
            _backupEvents.emit(event)
        }
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                proxyManager.stopProxy()
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()

            val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                appContext.startActivity(intent)
            }
        }
    }
}

enum class RestoreFailReason { BAD_PASSWORD, BAD_FILE, IO }

sealed interface BackupEvent {
    data object ExportSuccess : BackupEvent
    data object ExportFailed : BackupEvent
    data class RestoreSuccess(val count: Int) : BackupEvent
    data class RestoreFailed(val reason: RestoreFailReason) : BackupEvent
}
