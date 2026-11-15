package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NexaTheme

@Composable
fun NexaBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    val colors = NexaTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.elevatedSurface)
            .border(1.dp, colors.border, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val strokeWidth = (w * 0.13f).coerceAtLeast(2f)

            val path = Path().apply {
                moveTo(w * 0.16f, h * 0.84f)
                lineTo(w * 0.16f, h * 0.24f)
                lineTo(w * 0.84f, h * 0.78f)
                lineTo(w * 0.84f, h * 0.16f)
            }
            drawPath(
                path = path,
                color = colors.orange,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // Precision apex node
            drawCircle(
                color = colors.orange,
                radius = strokeWidth * 0.55f,
                center = Offset(w * 0.16f, h * 0.14f)
            )
        }
    }
}
