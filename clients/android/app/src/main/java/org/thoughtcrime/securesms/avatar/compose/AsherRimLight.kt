/*
 * Asher design system: avatar rim light.
 */

package org.thoughtcrime.securesms.avatar.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

private val RIM_START = Color(0xFF7FB8E0)
private val RIM_END = Color(0x007FB8E0)

/**
 * Draws Asher's 1.5dp rim-light ring over a circular avatar: lit from the top-left (135deg) and
 * fading to nothing by 70% of the way across, like the planet's limb. Apply after any [clip].
 */
fun Modifier.asherRimLight(width: Dp = 1.5.dp): Modifier = drawWithContent {
  drawContent()

  val stroke = width.toPx()
  val diameter = min(size.width, size.height)
  val radius = diameter / 2f - stroke / 2f
  if (radius <= 0f) return@drawWithContent

  drawCircle(
    brush = Brush.linearGradient(
      0f to RIM_START,
      0.7f to RIM_END,
      start = Offset.Zero,
      end = Offset(size.width, size.height)
    ),
    radius = radius,
    center = Offset(size.width / 2f, size.height / 2f),
    style = Stroke(width = stroke)
  )
}
