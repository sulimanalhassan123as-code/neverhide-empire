package com.neverhide.empire.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Empire Design System — the visual language of the Neverhide dashboard.
 * Every screen pulls from here so the whole app looks and feels identical.
 * Changing a color here re-themes every screen at once.
 */
object Palette {
    val BG = Color(0xFF0A0A1A)
    val BG2 = Color(0xFF0D1B2A)
    val CARD = Color(0xFF111827)
    val CARD_LIT = Color(0xFF16213A)
    val BORDER = Color(0xFF1E2A3A)
    val CYAN = Color(0xFF00E5FF)
    val PURPLE = Color(0xFF7C4DFF)
    val PINK = Color(0xFFFF4081)
    val ORANGE = Color(0xFFFF6D00)
    val GREEN = Color(0xFF69F0AE)
    val AMBER = Color(0xFFFFD600)
    val TEXT_DIM = Color(0xFF8899AA)
    val TEXT_MUTE = Color(0xFF5A6A7A)
    val WHITE = Color.White

    val heroGradient = Brush.linearGradient(listOf(Color(0xFF311B92), PURPLE))
    val cyanPulse = Brush.linearGradient(listOf(CYAN, Color(0xFF00838F)))
    val pageBg = Brush.verticalGradient(listOf(BG, BG2))
}

/** Frosted-glass category card — the signature container of the dashboard. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .shadow(
                elevation = if (glow != null) 18.dp else 8.dp,
                shape = shape,
                spotColor = glow ?: Color(0x33000000),
                ambientColor = Color(0x22000000)
            )
            .background(Palette.CARD, shape)
            .border(1.dp, glow?.copy(alpha = 0.35f) ?: Palette.BORDER, shape)
            .padding(18.dp),
        content = content
    )
}

/** Neon section header with the little colored accent bar. */
@Composable
fun SectionHeader(emoji: String, title: String, accent: Color = Palette.CYAN) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text("$emoji $title", color = Palette.WHITE, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** Gradient action button with glow shadow. */
@Composable
fun GlowButton(
    text: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .shadow(10.dp, shape, spotColor = colors.first().copy(alpha = 0.55f))
            .background(
                if (enabled) Brush.linearGradient(colors)
                else Brush.linearGradient(colors.map { it.copy(alpha = 0.35f) }), shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Color(0xFF0A0A1A) else Color(0xFF0A0A1A).copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Small status chip ("ACTIVE", "COMING SOON", "2 NEW"…). */
@Composable
fun StatusChip(text: String, color: Color, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(
                if (filled) color.copy(alpha = 0.18f) else Color(0x14000000),
                RoundedCornerShape(99.dp)
            )
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

/**
 * The big navigable category card used across the dashboard:
 * icon bubble + title + subtitle + trailing badge, opens a feature area.
 */
@Composable
fun FeatureCard(
    emoji: String,
    title: String,
    subtitle: String,
    accent: Color,
    badge: String? = null,
    badgeColor: Color = Palette.GREEN,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, spotColor = accent.copy(alpha = 0.30f))
            .background(Palette.CARD, shape)
            .border(1.dp, Palette.BORDER, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) { Text(emoji, fontSize = 21.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.WHITE, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = Palette.TEXT_DIM, fontSize = 12.sp, maxLines = 1)
        }
        if (badge != null) StatusChip(badge, badgeColor)
    }
}

/** Compact tile inside a category grid (icon over label). */
@Composable
fun ToolTile(emoji: String, label: String, accent: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.CARD, RoundedCornerShape(14.dp))
            .border(1.dp, Palette.BORDER, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Palette.TEXT_DIM, fontSize = 10.sp, maxLines = 1)
    }
}
