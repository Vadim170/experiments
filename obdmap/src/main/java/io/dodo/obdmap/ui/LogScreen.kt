package io.dodo.obdmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dodo.obdmap.util.Logger

/** Журнал работы. Открывается из настроек, отдельной вкладки не занимает. */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val lines by Logger.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GhostButton("← Назад", onClick = onBack)
            Text("Журнал", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Файлы: ${Logger.logDirPath()}",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Divider(Modifier.padding(vertical = 8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.contains("ОШИБКА")) Palette.Coral else Palette.TextSecondary,
                )
            }
        }
    }
}
