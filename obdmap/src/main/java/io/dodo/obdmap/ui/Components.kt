package io.dodo.obdmap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Свои элементы вместо стоковых Material: панель с обводкой вместо карточки с
 * тенью (в тёмной теме тень не видна), плоские пилюли вместо кнопок, крупные
 * моноширинные показания.
 */

/** Панель — основной контейнер. Обводка вместо тени. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PanelCorner)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(BorderStroke(1.dp, accent?.copy(alpha = 0.5f) ?: Palette.Outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        content = content,
    )
}

/** Заголовок раздела: мелкий, разреженный, приглушённый. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextSecondary,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
        }
    }
}

/**
 * Крупное показание. Значение моноширинное, чтобы цифры не прыгали по ширине
 * при каждом обновлении — за рулём это мельтешит.
 */
@Composable
fun Readout(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = Palette.Accent,
    size: Int = 38,
    caption: String? = null,
) {
    Panel(modifier) {
        if (caption != null) {
            Text(
                text = caption.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = value,
            style = ReadoutStyle.copy(fontSize = size.sp, color = accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Мелкое показание в ряду. */
@Composable
fun MiniStat(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Palette.TextPrimary,
) {
    Panel(modifier) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = ReadoutStyle.copy(fontSize = 17.sp, color = accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Плоская «пилюля» — переключатель режима. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Palette.Accent else Palette.SurfaceHigh)
            .border(
                BorderStroke(1.dp, if (selected) Palette.Accent else Palette.Outline),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Palette.Background else Palette.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Основная кнопка действия. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val shape = RoundedCornerShape(PanelCorner)
    val background = when {
        !enabled -> Palette.SurfaceHigh
        danger -> Palette.Coral
        else -> Palette.Accent
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) Palette.Background else Palette.TextMuted,
            maxLines = 1,
        )
    }
}

/** Второстепенная кнопка: только обводка. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(PanelCorner)
    Box(
        modifier = modifier
            .clip(shape)
            .border(BorderStroke(1.dp, Palette.Outline), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Palette.TextPrimary else Palette.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Точка состояния связи. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * Значок «i» рядом с заголовком: по нажатию объясняет, откуда взялись цифры.
 * Пояснение к расчёту — половина ценности графика.
 */
@Composable
fun InfoBadge(title: String, explanation: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Palette.SurfaceHigh)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "i",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = Palette.TextSecondary,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Palette.SurfaceHigh,
            title = { Text(title, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("Понятно", color = Palette.Accent)
                }
            },
        )
    }
}

/** Ряд с равномерно распределёнными мелкими показаниями. */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Разделитель. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
}

/** Пустое состояние с пояснением. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
        )
    }
}

/** Горизонтальный отступ между пилюлями. */
@Composable
fun PillGap() = Spacer(Modifier.width(6.dp))
