@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.CoreArgs
import com.freeturn.app.data.server.Server
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.util.redact
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import com.freeturn.app.ui.theme.Spacing

@Composable
fun NerdScreen(
    serverId: String,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val snapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val server = snapshot.list.firstOrNull { it.id == serverId }

    if (snapshot.loaded && server == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.nerd_section_title)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
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
                if (server != null) {
                    NerdContent(
                        server = server,
                        privacyMode = privacyMode,
                        onDebugModeChange = { v ->
                            settingsViewModel.updateServerClient(serverId) { it.copy(debugMode = v) }
                        },
                        onLogsEnabledChange = { v ->
                            settingsViewModel.updateServerClient(serverId) { it.copy(logsEnabled = v) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NerdContent(
    server: Server,
    privacyMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onLogsEnabledChange: (Boolean) -> Unit
) {
    val client = server.client

    // Per-server отладочные флаги. updateServerClient разводит active/inactive и
    // применяет logsEnabled живьём - отдельные VM-сеттеры не нужны.
    SettingsGroup {
        SettingsGroupItem(0, 2) {
            SettingsSwitchRow(
                title = stringResource(R.string.debug_mode),
                subtitle = stringResource(R.string.debug_mode_desc),
                checked = client.debugMode,
                onCheckedChange = onDebugModeChange
            )
        }
        SettingsGroupItem(1, 2) {
            SettingsSwitchRow(
                title = stringResource(R.string.logs_enabled),
                subtitle = stringResource(R.string.logs_enabled_desc),
                checked = client.logsEnabled,
                onCheckedChange = onLogsEnabledChange
            )
        }
    }

    LaunchParamsCard(server, privacyMode)
}

@Composable
private fun LaunchParamsCard(server: Server, privacyMode: Boolean) {
    val clientCmd = remember(server, privacyMode) { clientCommandLine(server, privacyMode) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(stringResource(R.string.nerd_launch_params), style = MaterialTheme.typography.titleMedium)
            LaunchParamBlock(stringResource(R.string.nerd_launch_client), clientCmd)
        }
    }
}

@Composable
private fun LaunchParamBlock(label: String, commandLine: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LogPane(commandLine)
    }
}

private val CLIENT_SECRET_FLAGS = setOf("-peer", "-link", "-obf-key", "-turn", "-client-id")

private fun clientCommandLine(server: Server, privacy: Boolean): String {
    val argv = CoreArgs.client(server.client, server.opts)
    val sb = StringBuilder("freeturn")
    var i = 0
    while (i < argv.size) {
        val tok = argv[i]
        sb.append(' ').append(tok)
        if (tok in CLIENT_SECRET_FLAGS && i + 1 < argv.size) {
            sb.append(' ').append(argv[i + 1].redact(privacy))
            i += 2
        } else {
            i += 1
        }
    }
    return sb.toString()
}

@Composable
private fun LogPane(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .padding(Spacing.md)
        )
    }
}
