package com.king400.songcutter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.king400.songcutter.core.Cut
import com.king400.songcutter.core.EnvelopeBuilder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * גל הקול עם שני סמנים לגרירה. הזמן תמיד משמאל לימין (גם בממשק בעברית).
 * גרירת סמן = הזזה · גרירה על הגל = בחירה חדשה · נגיעה = מעבר לנקודה.
 */
@Composable
fun Waveform(
    envelope: FloatArray,
    duration: Double,
    sel: Cut.Selection,
    viewStart: Double,
    viewDur: Double,
    pos: Double,
    onSelection: (Cut.Selection) -> Unit,
    onMove: (start: Boolean, t: Double) -> Unit,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val selNow by rememberUpdatedState(sel)
    val vs by rememberUpdatedState(viewStart)
    val vd by rememberUpdatedState(viewDur)
    val onSel by rememberUpdatedState(onSelection)
    val onMv by rememberUpdatedState(onMove)
    val onSk by rememberUpdatedState(onSeek)
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surfaceContainerHigh)
                .testTag("wave")
                .pointerInput(duration) {
                    val tol = 26.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        fun tAt(x: Float) = (vs + x / w * vd).coerceIn(0.0, duration)
                        fun xOf(t: Double) = ((t - vs) / vd * w).toFloat()
                        val ds = abs(down.position.x - xOf(selNow.start))
                        val de = abs(down.position.x - xOf(selNow.end))
                        val which: Boolean? = if (ds <= tol || de <= tol) ds <= de else null
                        val t0 = tAt(down.position.x)
                        var moved = false
                        down.consume()
                        drag(down.id) { change ->
                            if (!moved && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop / 2) moved = true
                            if (moved) {
                                val t = tAt(change.position.x)
                                if (which != null) onMv(which, t)
                                else onSel(Cut.Selection(minOf(t0, t), maxOf(t0, t)))
                            }
                            change.consume()
                        }
                        if (!moved) onSk(t0)
                    }
                },
        ) {
            val W = size.width
            val H = size.height
            val axis = 18.dp.toPx()
            val mid = (H - axis) / 2
            val amp = mid - 8.dp.toPx()
            fun x(t: Double) = ((t - viewStart) / viewDur * W).toFloat()
            val sx = x(sel.start)
            val ex = x(sel.end)

            drawRect(cs.primary.copy(alpha = 0.12f), Offset(sx, 0f), Size(ex - sx, H - axis))

            val step = 3.dp.toPx().coerceAtLeast(2f)
            val bar = step * 0.66f
            var px = 0f
            while (px < W) {
                val t0 = viewStart + px / W * viewDur
                val t1 = viewStart + (px + step) / W * viewDur
                if (t0 <= duration) {
                    val m = EnvelopeBuilder.peak(envelope, t0, t1)
                    val h = (sqrt(m.toDouble()) * amp).toFloat().coerceAtLeast(1.dp.toPx())
                    val inside = px >= sx && px <= ex
                    drawRect(
                        if (inside) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f),
                        Offset(px, mid - h), Size(bar, h * 2),
                    )
                }
                px += step
            }

            // ציר זמן
            val target = W / 90.dp.toPx()
            val steps = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0)
            val tick = steps.firstOrNull { viewDur / it <= target } ?: 3600.0
            var t = ceil(viewStart / tick) * tick
            while (t <= viewStart + viewDur) {
                val tx = x(t)
                drawRect(cs.onSurfaceVariant, Offset(tx, H - axis), Size(1.dp.toPx(), 4.dp.toPx()))
                val label = Cut.formatTime(t).let { if (tick >= 1) it.substringBeforeLast('.') else it }
                // מודדים קודם: תווית שלא נכנסת עד הקצה – לא מציירים (אחרת drawText קורס)
                val layout = measurer.measure(label, labelStyle)
                val lx = tx + 3.dp.toPx()
                if (lx >= 0 && lx + layout.size.width <= W) drawText(layout, topLeft = Offset(lx, H - axis + 2.dp.toPx()))
                t += tick
            }

            marker(sx, MarkerColors.start, H - axis)
            marker(ex, MarkerColors.end, H - axis)
            drawRect(cs.onSurface, Offset(x(pos) - 1f, 0f), Size(2.dp.toPx() / 1.5f, H - axis))
        }
    }
}

private fun DrawScope.marker(x: Float, color: Color, h: Float) {
    drawRect(color, Offset(x - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), h))
    val s = 10.dp.toPx()
    for (y in listOf(s + 2.dp.toPx(), h - s - 2.dp.toPx())) {
        val p = Path().apply {
            moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close()
        }
        drawPath(p, color)
    }
}
