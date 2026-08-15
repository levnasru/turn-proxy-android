package com.freeturn.app.viewmodel.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.backup.BackupCrypto
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServersSnapshot
import com.freeturn.app.data.server.Subscription
import com.freeturn.app.domain.subscription.XraySubscriptionFetcher
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

class SettingsViewModel(
    private val prefs: AppPreferences,
    private val proxyManager: LocalProxyManager,
    private val appUpdater: AppUpdater,
    private val orchestrator: ProxyOrchestrator,
    private val backupManager: BackupManager,
    private val subscriptionFetcher: XraySubscriptionFetcher,
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
