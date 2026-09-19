@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.splittunnel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.addBypassEntry
import com.freeturn.app.data.config.addDefaultBypassRules
import com.freeturn.app.data.config.normalizeBypassEntry
import com.freeturn.app.data.config.removeBypassEntry
import com.freeturn.app.data.config.splitTunnelSelection
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.data.AppChoice
import com.freeturn.app.data.installedInternetApps
import com.freeturn.app.ui.components.EmptyState
import com.freeturn.app.ui.theme.Spacing

@Composable
fun SplitTunnelModal(
    mode: String,
    apps: String,
    bypassRules: String,
    locked: Boolean,
    onModeChange: (String) -> Unit,
    onAppsChange: (String) -> Unit,
    onBypassRulesChange: (String) -> Unit,
    onDismiss: () -> Unit,
    containerColor: Color = BottomSheetDefaults.ContainerColor
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        SplitTunnelSheetContent(
            mode = mode,
            apps = apps,
            bypassRules = bypassRules,
            locked = locked,
            onModeChange = onModeChange,
            onAppsChange = onAppsChange,
            onBypassRulesChange = onBypassRulesChange
        )
    }
}

@Composable
fun SplitTunnelSheetContent(
    mode: String,
    apps: String,
    bypassRules: String,
    locked: Boolean,
    onModeChange: (String) -> Unit,
    onAppsChange: (String) -> Unit,
    onBypassRulesChange: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val splitOn = mode != SplitTunnelMode.ALL
    val controlsEnabled = splitOn && !locked
    // Последний выбранный "рабочий" режим, чтобы свитч вкл возвращал его, а не дефолт.
    var modeChoice by remember {
        mutableStateOf(if (mode != SplitTunnelMode.ALL) mode else SplitTunnelMode.EXCLUDE)
    }
    var query by remember { mutableStateOf("") }
    var newRuleInput by remember { mutableStateOf("") }

    // Пустой exclude-список показывает рос-сервисы отмеченными (тот же дефолт, что и в WG).
    val selected = remember(apps, modeChoice) { splitTunnelSelection(modeChoice, apps) }
    val installed by produceState<List<AppChoice>?>(initialValue = null, splitOn) {
        value = if (splitOn) context.installedInternetApps() else null
    }

    val ruleEntries = remember(bypassRules) {
        bypassRules.lineSequence()
            .map { normalizeBypassEntry(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = Spacing.sm, bottom = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.split_tunnel_title),
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (locked) {
            LockedBanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding)
            )
        }

        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    selectedTab = 0
                },
                text = { Text(stringResource(R.string.split_tunnel_tab_apps)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.apps_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                    selectedTab = 1
                },
                text = { Text(stringResource(R.string.split_tunnel_tab_sites)) },
                icon = {
                    Icon(
                        painterResource(R.drawable.public_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        if (selectedTab == 0) {
            // Вкладка "Приложения"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (splitOn) stringResource(R.string.split_tunnel_status_on)
                    else stringResource(R.string.split_tunnel_status_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = splitOn,
                    enabled = !locked,
                    onCheckedChange = { on ->
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onModeChange(if (on) modeChoice else SplitTunnelMode.ALL)
                    }
                )
            }

            ModeDropdown(
                mode = modeChoice,
                enabled = controlsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding),
                onSelect = { value ->
                    modeChoice = value
                    onModeChange(value)
                }
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                enabled = controlsEnabled,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding),
                label = { Text(stringResource(R.string.split_tunnel_search)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.search_24px), null)
                }
            )

            if (splitOn) {
                AppList(
                    modifier = Modifier
                        .height(ListHeight)
                        .fillMaxWidth(),
                    installed = installed,
                    selected = selected,
                    query = query,
                    enabled = controlsEnabled,
                    onToggle = { pkg ->
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        val next = if (pkg in selected) selected - pkg else selected + pkg
                        onAppsChange(next.sorted().joinToString("\n"))
                    }
                )
            } else {
                EmptyState(
                    iconRes = R.drawable.apps_24px,
                    desc = stringResource(R.string.split_tunnel_off_hint),
                    modifier = Modifier
                        .height(ListHeight)
                        .fillMaxWidth()
                )
            }
        } else {
            // Вкладка "Сайты и IP" (Whitelist/Bypass)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.public_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(R.string.bypass_rules_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Поле ввода + кнопка Добавить
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newRuleInput,
                    onValueChange = { newRuleInput = it },
                    enabled = !locked,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bypass_rules_input_placeholder)) },
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = {
                        if (newRuleInput.isNotBlank()) {
                            HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                            onBypassRulesChange(addBypassEntry(bypassRules, newRuleInput))
                            newRuleInput = ""
                        }
                    },
                    enabled = !locked && newRuleInput.isNotBlank()
                ) {
                    Icon(
                        painterResource(R.drawable.add_24px),
                        contentDescription = stringResource(R.string.bypass_rules_add)
                    )
                }
            }

            // Быстрые кнопки: Добавить РФ / Очистить
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onBypassRulesChange(addDefaultBypassRules(bypassRules))
                    },
                    enabled = !locked,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painterResource(R.drawable.add_24px),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.bypass_rules_add_defaults))
                }

                TextButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                        onBypassRulesChange("")
                    },
                    enabled = !locked && ruleEntries.isNotEmpty()
                ) {
                    Icon(
                        painterResource(R.drawable.delete_24px),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.bypass_rules_clear))
                }
            }

            // Список правил
            if (ruleEntries.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .height(ListHeight)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(ruleEntries, key = { it }) { rule ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HorizontalPadding),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.public_24px),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val isIp = rule.contains("/") ||
                                        (rule.split(".").size == 4 && rule.split(".").all { it.toIntOrNull() != null })
                                    Text(
                                        text = if (isIp) stringResource(R.string.bypass_rules_type_ip)
                                        else stringResource(R.string.bypass_rules_type_domain),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        HapticUtil.perform(context, HapticUtil.Pattern.SELECTION)
                                        onBypassRulesChange(removeBypassEntry(bypassRules, rule))
                                    },
                                    enabled = !locked
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.delete_24px),
                                        contentDescription = stringResource(R.string.bypass_rules_item_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                EmptyState(
                    iconRes = R.drawable.public_24px,
                    desc = stringResource(R.string.bypass_rules_empty),
                    modifier = Modifier
                        .height(ListHeight)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private val HorizontalPadding = 16.dp

private val ListHeight = 360.dp

@Composable
private fun LockedBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.info_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                stringResource(R.string.split_tunnel_locked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
