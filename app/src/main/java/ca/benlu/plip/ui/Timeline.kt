package ca.benlu.plip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.benlu.plip.Drink
import ca.benlu.plip.R
import ca.benlu.plip.Settings
import ca.benlu.plip.clock
import ca.benlu.plip.formatMl
import ca.benlu.plip.timeOfDay
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Within this many ml of the plan counts as on track. */
private const val ON_TRACK_ML = 100

/** Cumulative intake over the active hours, against a straight line that ends at the goal. */
@Composable
fun TimelineCard(drinks: List<Drink>, settings: Settings, day: LocalDate, isToday: Boolean) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    val zone = ZoneId.systemDefault()
    val span = ((settings.activeTo - settings.activeFrom + 1440) % 1440).takeIf { it > 0 } ?: 1440

    // Position of a moment within the active hours, 0 = start, 1 = end.
    fun fraction(time: Long): Float {
        val minute = Duration.between(day.atStartOfDay(zone), Instant.ofEpochMilli(time).atZone(zone)).toMinutes()
        var rel = minute - settings.activeFrom
        if (rel < 0 && settings.activeTo < settings.activeFrom) rel += 1440
        return (rel.toFloat() / span).coerceIn(0f, 1f)
    }

    val goal = settings.goal
    val total = drinks.sumOf { it.ml }
    val nowFraction = if (isToday) fraction(now) else 1f
    val status = when {
        total > goal -> "${stringResource(R.string.goal_reached)} · +${formatMl(total - goal)} ml"
        total == goal -> stringResource(R.string.goal_reached)
        !isToday -> stringResource(R.string.pace_missed, formatMl(goal - total))
        nowFraction <= 0f -> stringResource(R.string.pace_not_started, context.timeOfDay(settings.activeFrom))
        else -> {
            val diff = total - (goal * nowFraction).toInt()
            when {
                abs(diff) < ON_TRACK_ML -> stringResource(R.string.pace_on_track)
                diff < 0 -> stringResource(R.string.pace_behind, formatMl(-diff))
                else -> stringResource(R.string.pace_ahead, formatMl(diff))
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp),
            ) {
                val w = size.width
                val h = size.height
                // The goal is the ceiling: past it the line levels off, the subtitle says by how much.
                // It sits a little below the divider, so the dot never touches it.
                val headroom = 16.dp.toPx()
                fun y(ml: Int) = h - minOf(ml, goal).toFloat() / goal * (h - headroom)

                // Divider above the chart, then the plan towards the goal
                drawLine(colors.outlineVariant, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.dp.toPx())
                drawLine(
                    color = colors.outline,
                    start = Offset(0f, h),
                    end = Offset(w, y(goal)),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                )

                // What was actually drunk, as steps
                var sum = 0
                val line = Path().apply {
                    moveTo(0f, h)
                    drinks.sortedBy { it.time }.forEach { drink ->
                        val x = fraction(drink.time) * w
                        lineTo(x, y(sum))
                        sum += drink.ml
                        lineTo(x, y(sum))
                    }
                    lineTo(nowFraction * w, y(sum))
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(nowFraction * w, h)
                    close()
                }
                drawPath(area, colors.primary.copy(alpha = 0.15f))
                drawPath(line, colors.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (isToday) {
                    val dot = Offset(nowFraction * w, y(sum))
                    drawCircle(colors.surfaceContainer, radius = 7.dp.toPx(), center = dot)
                    drawCircle(colors.primary, radius = 5.dp.toPx(), center = dot)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(settings.activeFrom), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(clock(settings.activeTo), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendLine(colors.primary, dashed = false)
                Text(stringResource(R.string.ring_top), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                LegendLine(colors.outline, dashed = true)
                Text(stringResource(R.string.legend_plan), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean) {
    Canvas(Modifier.size(width = 18.dp, height = 4.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = if (dashed) 2.dp.toPx() else 3.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
        )
    }
}
