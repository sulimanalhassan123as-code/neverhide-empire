package com.neverhide.empire.dashboard

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp

/**
 * 🧊 NEUMORPHIC DESIGN SYSTEM (Soft UI) — v2.9.0 menu skin.
 *
 * The core trick: the element background EXACTLY matches the page
 * background (0% contrast golden rule), and depth is faked with a
 * dual shadow — dark glow toward the bottom-right (away from the
 * top-left light source), light glow toward the top-left.
 *
 * Theme switcher: CSS-custom-property equivalent — one NeuTheme
 * palette object drives every color on the dashboard; switching =
 * swapping the object + recomposition. Persisted in empire_prefs.
 */
data class NeuTheme(
    val key: String,
    val label: String,
    val dot: Color,
    val bg: Color,          // page AND card background — 0% contrast rule
    val shadowLight: Color, // top-left glow
    val shadowDark: Color,  // bottom-right glow
    val accent: Color,
    val textDark: Color,
    val textMuted: Color
)

object NeuThemes {
    val WHITE = NeuTheme("white", "White", Color(0xFFFFFFFF), Color(0xFFEEF2F6), Color(0xFFFFFFFF), Color(0xFFCAD1DC), Color(0xFFA31D1D), Color(0xFF2B3445), Color(0xFF7D899B))
    val BLACK = NeuTheme("black", "Black", Color(0xFF16181A), Color(0xFF16181A), Color(0xFF202326), Color(0xFF0C0D0E), Color(0xFFFF4757), Color(0xFFF1F5F9), Color(0xFF8A99AD))
    val RED = NeuTheme("red", "Red", Color(0xFFFF4D4D), Color(0xFF2B1215), Color(0xFF3A191D), Color(0xFF1C0B0E), Color(0xFFFF4D4D), Color(0xFFFECDD3), Color(0xFFFDA4AF))
    val GOLD = NeuTheme("gold", "Gold", Color(0xFFFBBF24), Color(0xFF221D17), Color(0xFF2F2820), Color(0xFF15120E), Color(0xFFFBBF24), Color(0xFFFEF3C7), Color(0xFFD97706))
    val SILVER = NeuTheme("silver", "Silver", Color(0xFFCBD5E1), Color(0xFFE2E8F0), Color(0xFFFFFFFF), Color(0xFFCBD5E1), Color(0xFF475569), Color(0xFF0F172A), Color(0xFF64748B))
    val BLUE = NeuTheme("blue", "Blue", Color(0xFF38BDF8), Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF080D1A), Color(0xFF38BDF8), Color(0xFFF8FAFC), Color(0xFF94A3B8))

    val ALL = listOf(BLACK, WHITE, RED, GOLD, SILVER, BLUE)
    fun from(key: String?): NeuTheme = ALL.firstOrNull { it.key == key } ?: BLACK
}

/**
 * .neu-flat — extruded surface: dark shadow bottom-right, light glow top-left.
 * Card background must equal the page background for the illusion to hold.
 */
fun Modifier.neuFlat(
    theme: NeuTheme,
    corner: Dp = 20.dp,
    offset: Dp = 6.dp,
    blur: Dp = 14.dp
): Modifier = this.drawBehind {
    val c = corner.toPx()
    val o = offset.toPx()
    val b = blur.toPx()
    val w = size.width
    val h = size.height
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(b, BlurMaskFilter.Blur.NORMAL)
    }
    // dark shadow — bottom-right
    paint.color = theme.shadowDark.toArgb()
    drawContext.canvas.nativeCanvas.drawRoundRect(
        RectF(0f, 0f, w + o, h + o), c, c, paint
    )
    // light glow — top-left
    paint.color = theme.shadowLight.toArgb()
    drawContext.canvas.nativeCanvas.drawRoundRect(
        RectF(-o, -o, w, h), c, c, paint
    )
}

/**
 * .neu-inset — carved-in surface, simulated with a light-to-dark gradient
 * border (light falls on the bottom-right of a hole).
 */
fun Modifier.neuInset(theme: NeuTheme, corner: Dp = 14.dp): Modifier = this.border(
    width = 2.dp,
    brush = Brush.linearGradient(
        colors = listOf(theme.shadowDark, theme.bg, theme.bg, theme.shadowLight),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(2600f, 2600f)
    ),
    shape = RoundedCornerShape(corner)
)

/** Extruded neumorphic card — background always equals the page background. */
@Composable
fun NeuCard(
    theme: NeuTheme,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .neuFlat(theme, corner)
            .background(theme.bg, RoundedCornerShape(corner))
            .padding(padding),
        content = content
    )
}

/** Extruded pill button. */
@Composable
fun NeuButton(
    theme: NeuTheme,
    text: String,
    modifier: Modifier = Modifier,
    accentText: Color? = null,
    corner: Dp = 14.dp,
    onClick: () -> Unit
) {
    Box(
        modifier
            .neuFlat(theme, corner, offset = 4.dp, blur = 10.dp)
            .background(theme.bg, RoundedCornerShape(corner))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = accentText ?: theme.textDark, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

/** Small carved-in chip (status / version). */
@Composable
fun NeuChip(
    theme: NeuTheme,
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier
            .neuInset(theme, 12.dp)
            .background(theme.bg, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (active) theme.accent else theme.textMuted,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold
        )
    }
}

/** 42dp extruded icon button (header actions). */
@Composable
fun NeuIconButton(theme: NeuTheme, emoji: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .neuFlat(theme, 12.dp, offset = 4.dp, blur = 10.dp)
            .background(theme.bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(emoji, fontSize = 17.sp) }
}

/** Quick-access tile: extruded 60dp box with colored icon badge + label. */
@Composable
fun NeuTile(
    theme: NeuTheme,
    emoji: String,
    label: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(60.dp)
                .neuFlat(theme, 18.dp, offset = 4.dp, blur = 10.dp)
                .background(theme.bg, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(32.dp).background(badgeColor.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = theme.textDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Uppercase muted section label. */
@Composable
fun NeuSectionLabel(text: String, theme: NeuTheme, modifier: Modifier = Modifier) {
    Text(
        text,
        color = theme.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier
    )
}

/** The empire status ring — outer extruded ring, inner carved-in circle. */
@Composable
fun NeuRing(
    theme: NeuTheme,
    label: String,
    value: String,
    sub: String,
    armed: Boolean = true
) {
    // Slow, endless rotation for the orbiting glow arc — "growing" motion around the ring.
    val infinite = rememberInfiniteTransition(label = "ringOrbit")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing)
        ),
        label = "angle"
    )
    // Gentle breathing pulse on the glow's opacity/width, so it feels alive, not just spinning.
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val glowColor = if (armed) theme.accent else theme.textMuted

    Box(Modifier.size(214.dp), contentAlignment = Alignment.Center) {
        // ===== Orbiting glow arc — deep animation growing around the circle =====
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (5.dp.toPx()) * pulse
            val inset = stroke / 2 + 3.dp.toPx()
            rotate(angle) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            glowColor.copy(alpha = 0f),
                            glowColor.copy(alpha = 0.15f * pulse),
                            glowColor.copy(alpha = 0.9f * pulse),
                            glowColor.copy(alpha = 0f)
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        // ===== Extruded ring body =====
        Box(
            Modifier
                .size(190.dp)
                .neuFlat(theme, corner = 95.dp)
                .background(theme.bg, CircleShape)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .neuInset(theme, corner = 90.dp)
                    .background(theme.bg, CircleShape)
                    .padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    label, color = theme.accent, fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp, letterSpacing = 1.5.sp, maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value, color = theme.textDark, fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp, maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    sub, color = theme.textMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp,
                    modifier = Modifier.fillMaxWidth(0.82f)
                )
            }
        }
    }
}

/** Theme picker popup — 3x2 grid of color dots, neumorphic card. */
@Composable
fun NeuThemePopup(
    theme: NeuTheme,
    activeKey: String,
    onPick: (String) -> Unit
) {
    NeuCard(theme, Modifier.width(220.dp), corner = 18.dp, padding = 14.dp) {
        NeuSectionLabel("CHOOSE COLOR", theme)
        Spacer(Modifier.height(10.dp))
        NeuThemes.ALL.chunked(3).forEach { rowThemes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                rowThemes.forEach { t ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(onClick = { onPick(t.key) })
                            .padding(4.dp)
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(t.dot, CircleShape)
                                .border(
                                    if (t.key == activeKey) 2.dp else 1.dp,
                                    if (t.key == activeKey) theme.textDark else theme.textMuted.copy(alpha = 0.4f),
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(t.label, color = theme.textDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
