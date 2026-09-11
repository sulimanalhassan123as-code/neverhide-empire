package com.neverhide.empire.wallpaper.effects

/**
 * The 20-wallpaper catalog — 5 variations across 4 element families
 * (Water, Fire, Galaxy, Hologram/Cyber). All GPU-driven, battery friendly.
 */
object EffectCatalog {

    data class Entry(
        val id: Int,
        val name: String,
        val emoji: String,
        val mode: Int,
        val colorA: FloatArray,
        val colorB: FloatArray,
        val count: Int,
        val pointSize: Float,
        val bg: FloatArray
    )

    // GLPoints motion modes
    private const val M_FIRE = 0
    private const val M_GALAXY = 1
    private const val M_HOLOGRAM = 2
    private const val M_RAIN = 3
    private const val M_SNOW = 4
    private const val M_MATRIX = 5
    private const val M_BUBBLES = 6
    private const val M_STARFIELD = 7

    private fun rgb(hex: Long, alpha: Float = 1f) = floatArrayOf(
        ((hex shr 16) and 0xFF) / 255f,
        ((hex shr 8) and 0xFF) / 255f,
        (hex and 0xFF) / 255f,
        alpha
    )

    val ALL: List<Entry> = listOf(
        // ===== WATER family 🌊 =====
        Entry(0, "Abyss Bubbles", "🫧", M_BUBBLES,
            rgb(0xFF4FC3F7), rgb(0xFF01579B), 700, 34f, rgb(0xFF001520)),
        Entry(1, "Ocean Rain", "🌧️", M_RAIN,
            rgb(0xFF4FC3F7), rgb(0xFF0277BD), 900, 22f, rgb(0xFF001820)),
        Entry(2, "Frozen Sleet", "❄️", M_SNOW,
            rgb(0xFFB3E5FC), rgb(0xFF4FC3F7), 800, 26f, rgb(0xFF001418)),
        Entry(3, "Deep Current", "🌊", M_GALAXY,
            rgb(0xFF00E5FF), rgb(0xFF0D47A1), 650, 30f, rgb(0xFF000A14)),
        Entry(4, "Tidal Drift", "🌀", M_STARFIELD,
            rgb(0xFF80DEEA), rgb(0xFF01579B), 750, 24f, rgb(0xFF001018)),

        // ===== FIRE family 🔥 =====
        Entry(5, "Ember Rise", "🔥", M_FIRE,
            rgb(0xFFFFD600), rgb(0xFFDD2C00), 700, 36f, rgb(0xFF120300)),
        Entry(6, "Inferno Storm", "🌋", M_FIRE,
            rgb(0xFFFF6D00), rgb(0xFFB71C1C), 900, 42f, rgb(0xFF1A0000)),
        Entry(7, "Lava Fall", "🩸", M_RAIN,
            rgb(0xFFFFAB40), rgb(0xFFBF360C), 850, 28f, rgb(0xFF140200)),
        Entry(8, "Phoenix Sparks", "🕊️", M_STARFIELD,
            rgb(0xFFFFEA00), rgb(0xFFE65100), 650, 32f, rgb(0xFF100400)),
        Entry(9, "Hell Gate", "👹", M_MATRIX,
            rgb(0xFFFF3D00), rgb(0xFF7F0000), 800, 38f, rgb(0xFF1B0000)),

        // ===== GALAXY family 🌌 =====
        Entry(10, "Galaxy Spiral", "🌌", M_GALAXY,
            rgb(0xFFE1BEE7), rgb(0xFF311B92), 800, 26f, rgb(0xFF050010)),
        Entry(11, "Starfield Warp", "✨", M_STARFIELD,
            rgb(0xFFFFFFFF), rgb(0xFF7C4DFF), 700, 24f, rgb(0xFF020008)),
        Entry(12, "Nebula Cloud", "☁️", M_SNOW,
            rgb(0xFFCE93D8), rgb(0xFF4A148C), 600, 40f, rgb(0xFF070312)),
        Entry(13, "Aurora Fall", "🎆", M_RAIN,
            rgb(0xFF69F0AE), rgb(0xFF00B8D4), 900, 24f, rgb(0xFF000E0A)),
        Entry(14, "Cosmic Dust", "💫", M_BUBBLES,
            rgb(0xFFFFCCBC), rgb(0xFF6A1B9A), 750, 30f, rgb(0xFF060208)),

        // ===== CYBER / HOLOGRAM family ⚡ =====
        Entry(15, "Neon Grid", "🔷", M_HOLOGRAM,
            rgb(0xFF00E5FF), rgb(0xFF1A237E), 600, 26f, rgb(0xFF000510)),
        Entry(16, "Matrix Rain", "💾", M_MATRIX,
            rgb(0xFF00E676), rgb(0xFF1B5E20), 900, 30f, rgb(0xFF000800)),
        Entry(17, "Cyber Pulse", "⚡", M_FIRE,
            rgb(0xFF00E5FF), rgb(0xFF304FFE), 700, 32f, rgb(0xFF000418)),
        Entry(18, "Hologram Core", "🥏", M_GALAXY,
            rgb(0xFF80D8FF), rgb(0xFF01579B), 650, 28f, rgb(0xFF000814)),
        Entry(19, "Void Shadows", "🌑", M_STARFIELD,
            rgb(0xFF7C4DFF), rgb(0xFF1A0040), 700, 36f, rgb(0xFF030008))
    )

    fun byId(id: Int): Entry = ALL.getOrElse(id) { ALL[0] }

    fun labels(): List<String> = ALL.map { "${it.emoji} ${it.name}" }
}
