package com.freeturn.app.ui.screens.connectionmode

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.theme.extendedColorScheme
import com.freeturn.app.ui.util.redact

/**
 * Прямой VLESS+XHTTP+Reality конфиг (тот же формат, что и в отдельном v2ray/Xray
 * клиенте на компьютере — весь JSON целиком, только users[].id меняется на
 * человека). Обязателен один "tun"-инбаунд — сам fd подставит RealityVpnService.
 */
@Composable
internal fun RealityConfigCard(
    xrayConfig: String,
    onXrayConfig: (String) -> Unit,
    privacyMode: Boolean,
    onLoadFile: () -> Unit
) {
    val configLoaded = xrayConfig.isNotBlank()
    SectionLabel(stringResource(R.string.connection_config_section))
    SettingsCard {
        SettingsEntryRow(
            iconRes = R.drawable.cloud_download_24px,
            title = stringResource(R.string.load_xray_json),
            trailingRes = if (configLoaded) R.drawable.check_circle_24px else null,
            trailingTint = MaterialTheme.extendedColorScheme.success,
            enabled = !privacyMode,
            onClick = onLoadFile
        )
        SettingsRowDivider()
        SettingsFieldSlot {
            // Конфиг содержит UUID/приватные данные подключения - под privacyMode маскируем,
            // как и WireGuard-конфиг рядом.
            OutlinedTextField(
                value = xrayConfig.redact(privacyMode),
                onValueChange = { if (!privacyMode) onXrayConfig(it) },
                label = { Text(stringResource(R.string.setup_xray_json_label)) },
                placeholder = { Text(stringResource(R.string.setup_xray_json_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp),
                maxLines = 14,
                readOnly = privacyMode
            )
        }
    }
}
