package io.dodo.obdmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.dodo.obdmap.data.HistoryStore
import io.dodo.obdmap.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Настройки. Сюда же уехал лог — он нужен, когда что-то не работает, а не
 * каждый день, и занимать им отдельную вкладку было расточительно.
 */
@Composable
fun SettingsScreen(
    autoMode: Boolean,
    adapterName: String?,
    adapterAddress: String?,
    onSetAutoMode: (Boolean) -> Unit,
    onPickAdapter: () -> Unit,
) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }

    if (showLog) {
        LogScreen(onBack = { showLog = false })
        return
    }

    var tank by remember { mutableFloatStateOf(Prefs.tankLiters(context)) }
    var limitMb by remember {
        mutableFloatStateOf(Prefs.maxStorageBytes(context) / 1024f / 1024f)
    }
    var thresholds by remember { mutableStateOf(Prefs.speedThresholds(context)) }
    var dbBytes by remember { mutableLongStateOf(-1L) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        dbBytes = withContext(Dispatchers.IO) { HistoryStore.databaseBytes(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        SectionTitle("Адаптер")
        Panel {
            Text(
                text = adapterName ?: adapterAddress ?: "не выбран",
                style = MaterialTheme.typography.titleSmall,
            )
            if (adapterName != null && adapterAddress != null) {
                Text(
                    text = adapterAddress,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Выбрать другой", onClick = onPickAdapter, modifier = Modifier.fillMaxWidth())
        }

        SectionTitle("Поездки")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автоматический режим", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Ждать адаптер и начинать поездку самому, " +
                            "когда заведён мотор",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                    )
                }
                Switch(
                    checked = autoMode,
                    onCheckedChange = onSetAutoMode,
                    enabled = adapterAddress != null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Background,
                        checkedTrackColor = Palette.Accent,
                        uncheckedTrackColor = Palette.SurfaceHigh,
                        uncheckedBorderColor = Palette.Outline,
                    ),
                )
            }
        }

        SectionTitle("Топливный бак")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Объём бака", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${tank.roundToInt()} л",
                    style = ReadoutStyle.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        color = Palette.Accent,
                    ),
                )
            }
            Slider(
                value = tank,
                onValueChange = { tank = it },
                onValueChangeFinished = { Prefs.setTankLiters(context, tank) },
                valueRange = 25f..120f,
                colors = sliderColors(),
            )
            Text(
                text = "Блок отдаёт уровень в процентах. Зная объём, показываем " +
                    "литры: сколько есть и сколько свободно.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }

        SectionTitle("Раскраска трека")
        Panel {
            Text("Пороги скорости, км/ч", style = MaterialTheme.typography.titleSmall)
            Text(
                text = thresholds.joinToString(" · ") { it.roundToInt().toString() },
                style = ReadoutStyle.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize,
                    color = Palette.Accent,
                ),
            )
            thresholds.forEachIndexed { index, value ->
                Slider(
                    value = value.toFloat(),
                    onValueChange = { updated ->
                        thresholds = thresholds.toMutableList().also { it[index] = updated.toDouble() }
                    },
                    onValueChangeFinished = { Prefs.setSpeedThresholds(context, thresholds) },
                    valueRange = 5f..200f,
                    colors = sliderColors(),
                )
            }
            Text(
                text = "Пороги задают опорные точки градиента: между ними цвет " +
                    "переходит плавно.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }

        SectionTitle("Хранилище")
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Лимит истории", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${limitMb.roundToInt()} МБ",
                    style = ReadoutStyle.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize,
                        color = Palette.Accent,
                    ),
                )
            }
            Slider(
                value = limitMb,
                onValueChange = { limitMb = it },
                onValueChangeFinished = {
                    Prefs.setMaxStorageBytes(context, (limitMb * 1024 * 1024).toLong())
                    reload++
                },
                valueRange = 50f..4000f,
                colors = sliderColors(),
            )
            val hours = HistoryStore.estimatedHours((limitMb * 1024 * 1024).toLong())
            Text(
                text = "Это примерно ${formatHours(hours)} записи в подробном виде. " +
                    "Когда лимит исчерпан, самые старые поездки автоматически " +
                    "пакуются в архив — примерно в ${HistoryStore.archiveRatio()} раз " +
                    "плотнее. В списке они остаются, открываются чуть медленнее.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (dbBytes < 0) "Считаю…" else "Занято сейчас: ${formatBytes(dbBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
            )
        }

        MapCacheCard()

        SectionTitle("Диагностика")
        Panel {
            Text("Журнал работы", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Что приложение говорит адаптеру и что слышит в ответ. " +
                    "Нужен, когда шина не отвечает.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            GhostButton("Открыть лог", onClick = { showLog = true }, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Accent,
    activeTrackColor = Palette.Accent,
    inactiveTrackColor = Palette.Outline,
)

private fun formatHours(hours: Double): String = when {
    hours >= 48 -> "${(hours / 24).roundToInt()} суток"
    hours >= 1 -> "${hours.roundToInt()} часов"
    else -> "${(hours * 60).roundToInt()} минут"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.1f ГБ", bytes / 1024.0 / 1024 / 1024)

    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f МБ", bytes / 1024.0 / 1024)
    else -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
}
