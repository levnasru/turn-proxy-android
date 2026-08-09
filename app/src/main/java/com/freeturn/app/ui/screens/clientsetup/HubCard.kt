package com.freeturn.app.ui.screens.clientsetup

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.freeturn.app.R
import com.freeturn.app.ui.components.LabeledTextField
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsFieldSlot
import com.freeturn.app.ui.components.SettingsRowDivider
import com.freeturn.app.ui.util.redact

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp

@Composable
internal fun HubCard(
    hubUrl: String,
    onHubUrl: (String) -> Unit,
    hubPin: String,
    onHubPin: (String) -> Unit,
    hubToken: String,
    onHubToken: (String) -> Unit,
    privacyMode: Boolean,
    onInjectCache: () -> Unit = {}
) {
    SettingsCard {
        SettingsFieldSlot {
            LabeledTextField(
                value = hubUrl.redact(privacyMode),
                onValueChange = { if (!privacyMode) onHubUrl(it) },
                labelRes = R.string.hub_url_label,
                placeholderRes = R.string.hub_url_placeholder,
                supportingRes = null,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            LabeledTextField(
                value = hubPin.redact(privacyMode),
                onValueChange = { if (!privacyMode) onHubPin(it) },
                labelRes = R.string.hub_pin_label,
                placeholderRes = R.string.hub_pin_placeholder,
                supportingRes = null,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
        SettingsRowDivider()
        SettingsFieldSlot {
            LabeledTextField(
                value = hubToken.redact(privacyMode),
                onValueChange = { if (!privacyMode) onHubToken(it) },
                labelRes = R.string.hub_token_label,
                placeholderRes = R.string.hub_token_placeholder,
                supportingRes = null,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
        SettingsRowDivider()
        TextButton(
            onClick = onInjectCache,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Вставить кэш вручную (Auto-cred Fallback)")
        }
    }
}
