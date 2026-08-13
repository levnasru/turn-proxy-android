@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServersSnapshot
import com.freeturn.app.data.server.Subscription
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.ServerRow
import com.freeturn.app.ui.components.settingsItemShape
import com.freeturn.app.ui.util.pasteFromClipboard
import com.freeturn.app.ui.util.redact
import com.freeturn.app.ui.theme.Spacing

@Composable
internal fun ServersSheetContent(
    snapshot: ServersSnapshot,
    subscriptions: List<Subscription> = emptyList(),
    privacyMode: Boolean = false,
    callLink: String = "",
    // Прокси запущен - правку ссылки на звонок блокируем (новая комната = реконнект).
    callLinkLocked: Boolean = false,
    onApplyServer: (String) -> Unit = {},
    onOpenServerSettings: (String) -> Unit = {},
    onSaveCallLink: (String) -> Unit = {},
    onRefreshSubscription: (String) -> Unit = {}
) {
    val active = snapshot.active
    // Менять ссылку можно только у сохранённого активного сервера и пока прокси стоит.
    val callLinkEditable = active != null && !callLinkLocked
    var showCallLinkDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl, vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val serverName = active?.name ?: stringResource(R.string.server_unsaved_label)
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(serverName) } },
                state = rememberTooltipState(),
                enableUserInput = active != null
            ) {
                Text(
                    serverName,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val sub = active?.client?.serverAddress?.takeIf { it.isNotBlank() }?.redact(privacyMode)
            Spacer(Modifier.height(4.dp))
            Text(
                sub.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        ProviderChip(
            current = active?.client?.provider ?: Provider.VK,
            editable = callLinkEditable,
            onClick = { showCallLinkDialog = true },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(20.dp))

        Text(
            stringResource(R.string.servers_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = Spacing.xxl, end = Spacing.lg, bottom = Spacing.sm)
        )

        // VK-TURN - плоская группа, как раньше. Xray делится на подгруппы по подписке
        // (Server.subscriptionId), плюс "Личные" - для вручную вставленных конфигов
        // без подписки. Порядок подгрупп внутри Xray - как в списке подписок.
        val vkTurnServers = snapshot.list.filter { it.client.tunnelTransport != TunnelTransport.REALITY }
        val xrayServers = snapshot.list.filter { it.client.tunnelTransport == TunnelTransport.REALITY }
        val bySubscription = xrayServers.filter { it.subscriptionId.isNotBlank() }.groupBy { it.subscriptionId }
        val manualXray = xrayServers.filter { it.subscriptionId.isBlank() }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            if (vkTurnServers.isNotEmpty()) {
                item(key = "group_vk_turn") {
                    SectionLabel(stringResource(R.string.server_group_vk_turn))
                }
                itemsIndexed(vkTurnServers, key = { _, p -> p.id }) { index, p ->
                    ServerRowItem(
                        server = p,
                        isActive = snapshot.activeId == p.id,
                        shape = settingsItemShape(index, vkTurnServers.size),
                        privacyMode = privacyMode,
                        onClick = onApplyServer,
                        onSettingsClick = onOpenServerSettings
                    )
                }
            }

            if (xrayServers.isNotEmpty()) {
                item(key = "group_xray") {
                    SectionLabel(stringResource(R.string.server_group_xray))
                }
                subscriptions.filter { it.id in bySubscription }.forEach { subscription ->
                    val group = bySubscription.getValue(subscription.id)
                    item(key = "sub_${subscription.id}") {
                        SubscriptionSubheader(
                            name = subscription.name,
                            onRefresh = { onRefreshSubscription(subscription.id) }
                        )
                    }
                    itemsIndexed(group, key = { _, p -> p.id }) { index, p ->
                        ServerRowItem(
                            server = p,
                            isActive = snapshot.activeId == p.id,
                            shape = settingsItemShape(index, group.size),
                            privacyMode = privacyMode,
                            onClick = onApplyServer,
                            onSettingsClick = onOpenServerSettings
                        )
                    }
                }
                if (manualXray.isNotEmpty()) {
                    item(key = "sub_manual") {
                        SubscriptionSubheader(name = stringResource(R.string.subscription_group_manual))
                    }
                    itemsIndexed(manualXray, key = { _, p -> p.id }) { index, p ->
                        ServerRowItem(
                            server = p,
                            isActive = snapshot.activeId == p.id,
                            shape = settingsItemShape(index, manualXray.size),
                            privacyMode = privacyMode,
                            onClick = onApplyServer,
                            onSettingsClick = onOpenServerSettings
                        )
                    }
                }
            }
        }
    }

    if (showCallLinkDialog) {
        CallLinkDialog(
            initial = callLink,
            onSave = { showCallLinkDialog = false; onSaveCallLink(it) },
            onDismiss = { showCallLinkDialog = false }
        )
    }
}

@Composable
private fun ServerRowItem(
    server: Server,
    isActive: Boolean,
    shape: Shape,
    privacyMode: Boolean,
    onClick: (String) -> Unit,
    onSettingsClick: (String) -> Unit
) {
    val sub = server.client.serverAddress.takeIf { it.isNotBlank() }?.redact(privacyMode) ?: "-"
    ServerRow(
        name = server.name,
        subtitle = sub,
        isActive = isActive,
        shape = shape,
        inactiveContainer = MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = { if (!isActive) onClick(server.id) },
        trailing = {
            IconButton(onClick = { onSettingsClick(server.id) }) {
                Icon(
                    painterResource(R.drawable.settings_outlined_24px),
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/** Заголовок подгруппы внутри Xray (имя подписки или "Личные") с опциональным обновлением. */
@Composable
private fun SubscriptionSubheader(name: String, onRefresh: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) {
                Icon(
                    painterResource(R.drawable.cloud_download_24px),
                    contentDescription = stringResource(R.string.subscription_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(
    current: String,
    editable: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val chipPadding = PaddingValues(start = Spacing.sm, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm)
    if (editable) {
        Button(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = chipPadding,
            modifier = modifier
        ) {
            ProviderChipContent(current, showEdit = true)
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.padding(chipPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ProviderChipContent(current, showEdit = false)
            }
        }
    }
}

@Composable
private fun RowScope.ProviderChipContent(current: String, showEdit: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, MaterialShapes.Sunny.toShape()),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.nearby_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp)
        )
    }
    Text(
        providerLabel(current),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(start = Spacing.sm)
    )
    if (showEdit) {
        Icon(
            painterResource(R.drawable.edit_24px),
            contentDescription = stringResource(R.string.call_link_edit),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .padding(start = Spacing.sm)
                .size(18.dp)
        )
    }
}

@Composable
private fun CallLinkDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialShapes.Sunny.toShape()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.link_24px),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        title = { Text(stringResource(R.string.call_link_label)) },
        text = {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(R.string.call_link_placeholder)) },
                supportingText = { Text(stringResource(R.string.call_link_support)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = {
                        context.pasteFromClipboard()?.takeIf { it.isNotBlank() }?.let {
                            link = it.trim()
                            HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.content_paste_24px),
                            contentDescription = stringResource(R.string.paste)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                    onSave(link.trim())
                },
                enabled = link.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun providerLabel(value: String): String = when (value) {
    Provider.VK -> stringResource(R.string.provider_vk)
    else -> value
}

