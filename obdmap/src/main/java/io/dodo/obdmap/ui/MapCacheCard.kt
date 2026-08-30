package io.dodo.obdmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Locale

/**
 * Состояние офлайн-кеша карт.
 *
 * Пакетной выкачки области здесь намеренно нет: стандартный сервер тайлов OSM
 * её запрещает, и osmdroid это соблюдает — политика источника MAPNIK помечена
 * FLAG_NO_BULK, а CacheManager на таком источнике просто бросает
 * TileSourcePolicyException. Поэтому офлайн набирается по-честному: всё, что
 * карта показала в поездке, остаётся на диске и потом открывается без сети.
 */
@Composable
fun MapCacheCard(modifier: Modifier = Modifier) {
    var sizeBytes by remember { mutableLongStateOf(-1L) }
    var reloadKey by remember { mutableStateOf(0) }

    val cacheDir = remember { Configuration.getInstance().osmdroidTileCache }

    LaunchedEffect(reloadKey) {
        sizeBytes = withContext(Dispatchers.IO) { directorySize(cacheDir) }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Офлайн-карта", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (sizeBytes < 0) {
                    "Считаю размер кеша…"
                } else {
                    "В кеше ${formatSize(sizeBytes)}. Всё, что карта показала в поездке, " +
                        "открывается потом без сети."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Пакетная выкачка области не делается: сервер тайлов OSM её запрещает.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    runCatching { cacheDir.deleteRecursively() }
                        .onFailure { Logger.error("не смог очистить кеш карт", it) }
                    cacheDir.mkdirs()
                    reloadKey++
                },
                enabled = sizeBytes > 0,
            ) {
                Text("Очистить кеш")
            }
        }
    }
}

private fun directorySize(dir: File): Long =
    if (!dir.exists()) 0 else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.1f ГБ", bytes / 1024.0 / 1024 / 1024)

    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f МБ", bytes / 1024.0 / 1024)
    else -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
}
