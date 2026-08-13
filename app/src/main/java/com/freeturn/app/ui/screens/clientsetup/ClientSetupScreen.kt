@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.clientsetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.HubCacheInjectDialog
import com.freeturn.app.ui.components.LabeledTextField
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsControlLabel
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.redact
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ClientSetupScreen(
    settingsViewModel: SettingsViewModel,
    // null = активный сервер; не-null = конкретный сервер по id (Settings-флоу).
    serverId: String? = null,
    onBack: (() -> Unit)? = null
) {
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val activeClient by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()

    // Источник данных: конкретный сервер по id либо активный.
    val server = serverId?.let { id -> snapshot.list.firstOrNull { it.id == id } }
    val saved = server?.client ?: activeClient
    // Активный сервер рулит живым рантаймом, неактивный - только хранилищем.
    val isActive = serverId == null || serverId == snapshot.activeId

    // Единая точка записи client-конфига: сервер by-id либо активный.
    fun clientEdit(transform: (ClientConfig) -> ClientConfig) {
        if (serverId != null) {
            settingsViewModel.updateServerClient(serverId, transform)
        } else {
            settingsViewModel.saveClientConfig(transform(settingsViewModel.clientConfig.value), snapshot.activeId)
        }
    }

    val effectiveTcpForward = saved.tcpForward

    val context = LocalContext.current

    // remember (не rememberSaveable), чтобы не восстанавливать stale-поля из bundle.
    val fieldsKey = serverId ?: snapshot.activeId
    var serverAddress by remember(fieldsKey) { mutableStateOf(saved.serverAddress) }
    var vkLink       by remember(fieldsKey) { mutableStateOf(saved.vkLink) }
    var threads      by remember(fieldsKey) { mutableFloatStateOf(saved.threads.toFloat()) }
    var streamsPerCred by remember(fieldsKey) { mutableFloatStateOf(saved.streamsPerCred.toFloat()) }
    var localPort    by remember(fieldsKey) { mutableStateOf(saved.localPort) }
    var magicTurn    by remember(fieldsKey) { mutableStateOf(saved.magicTurn) }
    var customDns    by remember(fieldsKey) { mutableStateOf(saved.customDns) }
    var hubUrl       by remember(fieldsKey) { mutableStateOf(saved.hubUrl) }
    var hubPin       by remember(fieldsKey) { mutableStateOf(saved.hubPin) }
    var hubToken     by remember(fieldsKey) { mutableStateOf(saved.hubToken) }
    var rawCommand   by remember(fieldsKey) { mutableStateOf(saved.rawCommand) }

    var showInjectDialog by remember { mutableStateOf(false) }

    // Поля живут своей жизнью с момента первой правки. До этого догоняем DataStore:
    // clientConfig стартует с дефолта и реальный конфиг приезжает уже после композиции.
    var fieldsDirty by remember(fieldsKey) { mutableStateOf(false) }
    LaunchedEffect(fieldsKey, saved) {
        if (fieldsDirty) return@LaunchedEffect
        serverAddress = saved.serverAddress
        vkLink = saved.vkLink
        threads = saved.threads.toFloat()
        streamsPerCred = saved.streamsPerCred.toFloat()
        localPort = saved.localPort
        magicTurn = saved.magicTurn
        customDns = saved.customDns
        hubUrl = saved.hubUrl
        hubPin = saved.hubPin
        hubToken = saved.hubToken
        rawCommand = saved.rawCommand
    }

    // Авто-сохранение с дебаунсом 600 мс.
    LaunchedEffect(
        fieldsKey, serverAddress, vkLink, threads, streamsPerCred, localPort, magicTurn, customDns, hubUrl, hubPin, hubToken, rawCommand
    ) {
        if (!fieldsDirty) return@LaunchedEffect
        delay(600)
        clientEdit { current ->
            current.copy(
                serverAddress = serverAddress.trim(),
                vkLink        = vkLink.trim(),
                threads       = threads.roundToInt(),
                streamsPerCred = streamsPerCred.roundToInt(),
                localPort     = localPort.trim(),
                magicTurn     = magicTurn.trim(),
                customDns     = customDns.trim(),
                hubUrl        = hubUrl.trim(),
                hubPin        = hubPin.trim(),
                hubToken      = hubToken.trim(),
                rawCommand    = rawCommand.trim()
            )
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.provider_connection_settings)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.arrow_back_24px),
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = if (onBack != null) WindowInsets(0, 0, 0, 0) else WindowInsets.navigationBars
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                // Raw-режим: подключение целиком задано импортированным конфигом
                // (rawCommand перекрывает все поля ниже, структурные Provider.HUB-поля
                // сюда не доходят). Показывать их = мусорные мёртвые контролы, которые
                // ничего не меняют и путают. Вместо них - сама rawCommand как editable
                // поле: это единственный путь редактировать импортированный семейный
                // профиль (make-backup.js всегда шлёт isRawMode=true), без него
                // единственный способ поменять что-то - новый импорт с нуля.
                if (saved.isRawMode) {
                    SectionLabel(stringResource(R.string.provider_connection_settings))
                    SettingsCard {
                        SettingsFieldSlot {
                            SettingsControlLabel(
                                title = stringResource(R.string.raw_mode_title),
                                desc = stringResource(R.string.raw_mode_desc)
                            )
                        }
                        SettingsRowDivider()
                        SettingsFieldSlot {
                            LabeledTextField(
                                value = rawCommand.redact(privacyMode),
                                onValueChange = { if (!privacyMode) { rawCommand = it; fieldsDirty = true } },
                                labelRes = R.string.raw_mode_field_label,
                                readOnly = privacyMode,
                                singleLine = false
                            )
                        }
                    }
                } else {
                    ConnectionCard(
                        serverAddress = serverAddress,
                        onServerAddress = { serverAddress = it; fieldsDirty = true },
                        showVkLink = saved.provider == Provider.VK,
                        vkLink = vkLink,
                        onVkLink = { vkLink = it; fieldsDirty = true },
                        localPort = localPort,
                        onLocalPort = { localPort = it; fieldsDirty = true },
                        privacyMode = privacyMode
                    )

                    if (saved.provider == Provider.HUB) {
                        SectionLabel("Настройки Хаба") // TODO: move to string resource
                        HubCard(
                            hubUrl = hubUrl,
                            onHubUrl = { hubUrl = it; fieldsDirty = true },
                            hubPin = hubPin,
                            onHubPin = { hubPin = it; fieldsDirty = true },
                            hubToken = hubToken,
                            onHubToken = { hubToken = it; fieldsDirty = true },
                            privacyMode = privacyMode,
                            onInjectCache = { showInjectDialog = true }
                        )
                    }


                    PerformanceCard(
                        threads = threads,
                        // потоки-на-аккаунт не могут превышать общее число потоков
                        onThreads = {
                            threads = it
                            if (streamsPerCred > it) streamsPerCred = it
                            fieldsDirty = true
                        },
                        streamsPerCred = streamsPerCred,
                        onStreamsPerCred = { streamsPerCred = it.coerceAtMost(threads); fieldsDirty = true },
                        onTick = { HapticUtil.perform(context, HapticUtil.Pattern.SELECTION) }
                    )

                    DnsCard(
                        dnsMode = saved.dnsMode,
                        onDnsMode = { mode ->
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            clientEdit { it.copy(dnsMode = mode) }
                        },
                        customDns = customDns,
                        onCustomDns = { customDns = it; fieldsDirty = true },
                        useCarrierDns = saved.useCarrierDns,
                        onUseCarrierDns = { v -> clientEdit { it.copy(useCarrierDns = v) } }
                    )

                    AdvancedSection(
                        useUdp = saved.useUdp,
                        onUseUdp = { v ->
                            HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                            clientEdit { it.copy(useUdp = v) }
                        },
                        manualCaptcha = saved.manualCaptcha,
                        onManualCaptcha = { v -> clientEdit { it.copy(manualCaptcha = v) } },
                        showBond = effectiveTcpForward,
                        bond = saved.bond,
                        // bond триггерит рестарт прокси только у активного; иначе пишем данные.
                        onBond = { v -> if (isActive) settingsViewModel.setBond(v) else clientEdit { it.copy(bond = v) } },
                        magicSwitch = saved.magicSwitch,
                        onMagicSwitch = { v -> clientEdit { it.copy(magicSwitch = v) } },
                        magicTurn = magicTurn,
                        onMagicTurn = { magicTurn = it; fieldsDirty = true },
                        privacyMode = privacyMode
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
        
        if (showInjectDialog) {
            HubCacheInjectDialog(onDismissRequest = { showInjectDialog = false })
        }
    }
}
