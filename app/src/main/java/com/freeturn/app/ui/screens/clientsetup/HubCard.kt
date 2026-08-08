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

@Composable
internal fun HubCard(
    hubUrl: String,
    onHubUrl: (String) -> Unit,
    hubPin: String,
    onHubPin: (String) -> Unit,
    hubToken: String,
    onHubToken: (String) -> Unit,
    privacyMode: Boolean
) {
    SettingsCard {
        SettingsFieldSlot {
            LabeledTextField(
                value = hubUrl.redact(privacyMode),
                onValueChange = { if (!privacyMode) onHubUrl(it) },
                labelRes = R.string.hub_url_label,
                placeholderRes = R.string.hub_url_placeholder,
                supportingRes = 0,
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
                supportingRes = 0,
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
                supportingRes = 0,
                readOnly = privacyMode,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
    }
}
