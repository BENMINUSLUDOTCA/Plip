package ca.benlu.plip.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.benlu.plip.Drink
import ca.benlu.plip.Plip
import ca.benlu.plip.PlipState
import ca.benlu.plip.R
import ca.benlu.plip.clock
import ca.benlu.plip.formatMl
import ca.benlu.plip.intervalLabel
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MIN = 60_000L

/** Ring on the left, three stat tiles on the right. */
@Composable
fun Overview(state: PlipState, drinks: List<Drink>, isToday: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrinkRing(
            total = drinks.sumOf { it.ml },
            goal = state.settings.goal,
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isToday) {
                ReminderTile(state)
            } else {
                StatTile(rememberVectorPainter(Icons.Outlined.Notifications), stringResource(R.string.tile_entries), "${drinks.size}", 0f)
            }
            LastDrinkTile(drinks, isToday)
            StatTile(
                icon = painterResource(R.drawable.ic_flame),
                label = stringResource(R.string.tile_streak),
                value = if (state.streak == 0) {
                    stringResource(R.string.streak_none)
                } else {
                    pluralStringResource(R.plurals.streak_days, state.streak, state.streak)
                },
                fill = state.streak / 7f,
            )
        }
    }
}

@Composable
private fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(15_000)
        value = System.currentTimeMillis()
    }
}

// ---------- ring ----------

/** The second lap stops short of a full circle so its end stays visible, however much more was drunk. */
private const val MAX_EXTRA_LAP = 0.92f

@Composable
private fun DrinkRing(total: Int, goal: Int, modifier: Modifier) {
    // Up to two laps: the second one shows how far past the goal today is.
    val progress by animateFloatAsState(
        targetValue = (total.toFloat() / goal).coerceIn(0f, 1f + MAX_EXTRA_LAP),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "progress",
    )
    val colors = MaterialTheme.colorScheme
    val topLabel = colors.onSurfaceVariant.toArgb()
    val bottomLabel = colors.primary.toArgb()
    val extraLabel = colors.onPrimary.toArgb()
    val topText = stringResource(R.string.ring_top)
    val bottomText = if (total >= goal) stringResource(R.string.goal_reached) else stringResource(R.string.ring_of, formatMl(goal))
    val extraText = "+${formatMl(total - goal)}"

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.17f
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            val cx = size.width / 2
            val cy = size.height / 2
            val ringRadius = (size.minDimension - stroke) / 2
            drawArc(colors.surfaceContainerHighest, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            if (progress > 0f) {
                // Starts at the bottom like the Pixel widgets
                drawArc(colors.primary, 90f, 360f * progress.coerceAtMost(1f), false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val extra = progress - 1f
            if (extra > 0f) {
                val end = 90f + 360f * extra
                val endRad = Math.toRadians(end.toDouble())
                val tip = Offset(cx + ringRadius * cos(endRad).toFloat(), cy + ringRadius * sin(endRad).toFloat())
                // A rim in the background color around the rounded end lifts the second lap off the full ring below.
                drawCircle(colors.background, radius = stroke / 2 + stroke * 0.12f, center = tip)
                drawArc(colors.primary, 90f, 360f * extra, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))

                val extraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = stroke * 0.42f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    color = extraLabel
                }
                // Along the ring, ending just before the cap.
                val width = extraPaint.measureText(extraText)
                val sweep = Math.toDegrees((width / ringRadius).toDouble()).toFloat()
                val padding = Math.toDegrees((stroke * 0.25f / ringRadius).toDouble()).toFloat()
                val start = end - padding - sweep
                // In the lower half the text runs the other way round, so it never stands on its head.
                val lowerHalf = ((start + sweep / 2) % 360f + 360f) % 360f in 0f..180f
                val path = Path().apply {
                    val oval = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
                    if (lowerHalf) addArc(oval, start + sweep, -sweep) else addArc(oval, start, sweep)
                }
                drawIntoCanvas { it.nativeCanvas.drawTextOnPath(extraText, path, 0f, extraPaint.textSize * 0.35f, extraPaint) }
            }

            val textSize = 14.sp.toPx()
            val gap = 6.dp.toPx()
            val inner = size.minDimension / 2 - stroke
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawIntoCanvas { canvas ->
                // Top: path runs left to right over the top, glyphs grow outward toward the ring.
                curvedText(canvas.nativeCanvas, topText, cx, cy, inner - gap - textSize * 0.75f, top = true, paint.apply { color = topLabel })
                // Bottom: path runs left to right under the bottom, glyphs grow inward.
                curvedText(canvas.nativeCanvas, bottomText, cx, cy, inner - gap, top = false, paint.apply { color = bottomLabel })
            }
        }
        Text(
            text = formatMl(total),
            fontSize = 40.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
        )
    }
}

private fun curvedText(canvas: android.graphics.Canvas, text: String, cx: Float, cy: Float, radius: Float, top: Boolean, paint: Paint) {
    val path = Path().apply {
        addArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), 180f, if (top) 180f else -180f)
    }
    val offset = ((PI * radius).toFloat() - paint.measureText(text)) / 2
    canvas.drawTextOnPath(text, path, offset, 0f, paint)
}

// ---------- tiles ----------

/** A tile whose background fills up like a progress bar. A fill of 0 renders it neutral. */
@Composable
private fun StatTile(
    icon: Painter,
    label: String,
    value: String,
    fill: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val animated by animateFloatAsState(fill.coerceIn(0f, 1f), tween(700), label = "fill")
    val colors = MaterialTheme.colorScheme
    val active = fill > 0f
    val content = if (active) colors.onPrimaryContainer else colors.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (active) colors.primaryContainer.copy(alpha = 0.5f) else colors.surfaceContainerHigh),
    ) {
        if (animated > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .background(colors.primaryContainer),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceContainerLowest.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = if (active) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.titleLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ReminderTile(state: PlipState) {
    val context = LocalContext.current
    val now by rememberNow()
    var menuOpen by remember { mutableStateOf(false) }
    val s = state.settings
    val paused = state.pausedUntil > now
    val active = Plip.isActive(s, LocalTime.now())
    val minsLeft = ((state.nextAt - now) / MIN).toInt() + 1

    val value = when {
        paused -> context.getString(R.string.until_time, clock(state.pausedUntil))
        !active -> context.getString(R.string.quiet_hours)
        minsLeft <= 1 -> context.getString(R.string.soon)
        minsLeft < 60 -> context.getString(R.string.in_minutes, minsLeft)
        else -> context.getString(R.string.at_clock, clock(state.nextAt))
    }
    // Fills up as the next reminder gets closer
    val fill = if (paused || !active) 0f else (1f - (state.nextAt - now).toFloat() / (s.interval * MIN)).coerceIn(0.02f, 1f)

    Box {
        StatTile(
            icon = rememberVectorPainter(Icons.Outlined.Notifications),
            label = stringResource(if (paused) R.string.tile_paused else R.string.tile_reminder),
            value = value,
            fill = fill,
            onClick = { if (paused) Plip.resume(context) else menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(30, 60, 120).forEach { m ->
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.pause_for, context.intervalLabel(m))) },
                    onClick = {
                        Plip.pause(context, m)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LastDrinkTile(drinks: List<Drink>, isToday: Boolean) {
    val context = LocalContext.current
    val now by rememberNow()
    val last = drinks.maxByOrNull { it.time }
    val value = when {
        last == null -> context.getString(if (isToday) R.string.last_none_today else R.string.last_none)
        !isToday -> context.getString(R.string.at_clock, clock(last.time))
        else -> {
            val mins = ((now - last.time) / MIN).toInt()
            when {
                mins < 1 -> context.getString(R.string.just_now)
                mins < 60 -> context.getString(R.string.minutes_ago, mins)
                else -> context.getString(R.string.at_clock, clock(last.time))
            }
        }
    }
    StatTile(painterResource(R.drawable.ic_history), stringResource(R.string.tile_last), value, 0f)
}
