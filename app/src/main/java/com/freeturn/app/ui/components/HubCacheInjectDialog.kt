package com.freeturn.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Диалог ручной вставки JSON-кэша хаба - fallback, когда хаб недостижим
 * (оператор блокирует, anonymToken протух и т.п.). Пишет прямо в файл,
 * который ждёт ядро (`-hub-cache hubcreds-cache.json`, см. CoreArgs).
 */
@Composable
fun HubCacheInjectDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    var cacheJson by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Вставить кэш хаба") },
        text = {
            OutlinedTextField(
                value = cacheJson,
                onValueChange = { cacheJson = it },
                label = { Text("JSON кэш") },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    File(context.filesDir, "hubcreds-cache.json").writeText(cacheJson)
                    Toast.makeText(context, "Кэш сохранён", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка", Toast.LENGTH_SHORT).show()
                }
                onDismissRequest()
            }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Отмена")
            }
        }
    )
}
