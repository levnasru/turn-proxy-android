@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.serverdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.redact
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/**
 * Хаб сервера: без self-hosted SSH-стека (снесён целиком, см. память remove-ssh-stack) -
 * тут только клиентские настройки. "Настройки подключения" (VK-TURN hub-креды/DNS/
 * производительность) скрыты для Reality-профилей - см. ClientSetupScreen.
 */
@Composable
fun ServerDetailScreen(
    serverId: String,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenConnection: (String) -> Unit,
    onOpenConnectionMode: (String) -> Unit,
    onOpenNerdInfo: (String) -> Unit,
    onCloned: (String) -> Unit
) {
    val context = LocalContext.current
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val server = snapshot.list.firstOrNull { it.id == serverId }
    val isReality = server?.client?.tunnelTransport == com.freeturn.app.data.config.TunnelTransport.REALITY

    // Сервер удалён (например, из этого же экрана) - выходим назад.
    if (snapshot.loaded && server == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val headerSubtitle = server?.client?.serverAddress?.takeIf { it.isNotBlank() }?.redact(privacyMode)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(server?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                subtitle = headerSubtitle?.let { sub ->
                    { Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            if (server != null) {
                ServerActionsFab(
                    onRename = { showRename = true },
                    onClone = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        settingsViewModel.cloneServer(serverId, onCloned)
                    }
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                SectionLabel(stringResource(R.string.provider_vk_calls))
                val entryCount = if (isReality) 1 else 2
                SettingsGroup {
                    var entryIndex = 0
                    if (!isReality) {
                        SettingsGroupItem(entryIndex++, entryCount) {
                            SettingsEntryRow(
                                iconRes = R.drawable.mobile_24px,
                                title = stringResource(R.string.provider_connection_settings),
                                subtitle = stringResource(R.string.provider_connection_settings_desc),
                                onClick = { onOpenConnection(serverId) }
                            )
                        }
                    }
                    SettingsGroupItem(entryIndex, entryCount) {
                        SettingsEntryRow(
                            iconRes = R.drawable.wifi_24px,
                            title = stringResource(R.string.connection_mode_title),
                            subtitle = stringResource(R.string.provider_connection_mode_desc),
                            onClick = { onOpenConnectionMode(serverId) }
                        )
                    }
                }

                if (nerdMode && server != null) {
                    SettingsCard {
                        SettingsEntryRow(
                            iconRes = R.drawable.terminal_24px,
                            title = stringResource(R.string.nerd_section_title),
                            subtitle = stringResource(R.string.nerd_section_desc),
                            onClick = { onOpenNerdInfo(serverId) }
                        )
                    }
                }

                if (server != null) {
                    SectionLabel(stringResource(R.string.server_management))
                    SettingsGroup {
                        SettingsGroupItem(0, 1) {
                            SettingsEntryRow(
                                iconRes = R.drawable.delete_24px,
                                title = stringResource(R.string.server_delete_app),
                                subtitle = stringResource(R.string.server_delete_app_subtitle),
                                trailingRes = null,
                                iconContainer = MaterialTheme.colorScheme.errorContainer,
                                iconTint = MaterialTheme.colorScheme.onErrorContainer,
                                titleColorOverride = MaterialTheme.colorScheme.error,
                                onClick = { showDelete = true }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(88.dp))
            }
        }
    }

    if (showDelete && server != null) {
        DeleteServerDialog(
            serverName = server.name,
            onConfirm = {
                settingsViewModel.deleteServer(serverId)
                showDelete = false
            },
            onDismiss = { showDelete = false }
        )
    }

    if (showRename && server != null) {
        RenameServerDialog(
            currentName = server.name,
            onSave = { name ->
                settingsViewModel.renameServer(serverId, name)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
}

@Composable
private fun ServerActionsFab(
    onRename: () -> Unit,
    onClone: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = it }
            ) {
                Icon(
                    painterResource(R.drawable.more_vert_24px),
                    contentDescription = stringResource(R.string.server_actions),
                    tint = if (expanded) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    ) {
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onRename() },
            icon = { Icon(painterResource(R.drawable.edit_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.menu_rename_server)) }
        )
        FloatingActionButtonMenuItem(
            onClick = { expanded = false; onClone() },
            icon = { Icon(painterResource(R.drawable.content_copy_24px), contentDescription = null) },
            text = { Text(stringResource(R.string.menu_clone_server)) }
        )
    }
}
