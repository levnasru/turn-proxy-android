@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.freeturn.app.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freeturn.app.R
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.ui.theme.Spacing

data class ProtocolItem(
    val transport: String,
    val titleRes: Int,
    val descRes: Int
)

val PROTOCOL_ITEMS = listOf(
    ProtocolItem(TunnelTransport.WIREGUARD, R.string.protocol_bar_wg, R.string.protocol_bar_wg_desc),
    ProtocolItem(TunnelTransport.VK_XRAY, R.string.protocol_bar_vk_xray, R.string.protocol_bar_vk_xray_desc),
    ProtocolItem(TunnelTransport.AMNEZIA, R.string.protocol_bar_awg, R.string.protocol_bar_awg_desc),
    ProtocolItem(TunnelTransport.REALITY, R.string.protocol_bar_reality, R.string.protocol_bar_reality_desc)
)

/**
 * Быстрая панель переключения протокола туннелирования на главном экране.
 * Позволяет в один тап переключиться между:
 * 1. VK-WG: VK-TURN + WireGuard (минимальный пинг, UDP-режим)
 * 2. VK-Xray: VK-TURN + VLESS через TCP :56003 (обход блокировок WG и UDP)
 * 3. AWG: AmneziaWG (обфусцированный WireGuard с защитой от сигнатур ТСПУ)
 * 4. Reality: Прямой VLESS+XHTTP+Reality на VPS мимо VK-TURN
 */
@Composable
fun QuickProtocolBar(
    currentTransport: String,
    onSelectTransport: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val activeIndex = PROTOCOL_ITEMS.indexOfFirst { it.transport == currentTransport }.let {
        if (it >= 0) it else 0
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            PROTOCOL_ITEMS.forEachIndexed { index, item ->
                val isSelected = currentTransport == item.transport ||
                    (index == 0 && currentTransport == TunnelTransport.NONE)

                SegmentedButton(
                    selected = isSelected,
                    onClick = { onSelectTransport(item.transport) },
                    shape = SegmentedButtonDefaults.itemShape(index, PROTOCOL_ITEMS.size),
                    enabled = enabled
                ) {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val activeItem = PROTOCOL_ITEMS.getOrNull(activeIndex) ?: PROTOCOL_ITEMS[0]
        AnimatedContent(
            targetState = stringResource(activeItem.descRes),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "protocol_desc"
        ) { desc ->
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}
