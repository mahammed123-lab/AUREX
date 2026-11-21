package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object AurexColors {
    val Emerald = Color(0xFFF2F2F2)
    val EmeraldBright = Color(0xFFFFFFFF)
    val EmeraldDeep = Color(0xFFC8C8C8)
    val EmeraldDark = Color(0xFF9A9A9A)
    val Teal = Color(0xFF242424)
    val Cyan = Color(0xFFD6D6D6)
    val Blue = Color(0xFF3B82F6)
    val BlueLight = Color(0xFF60A5FA)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Purple = Color(0xFFA855F7)
    val Pink = Color(0xFFEC4899)
    val Sky = Color(0xFF0EA5E9)
    val Violet = Color(0xFF7C3AED)

    val StatusListening = Color(0xFFC4C4C4)
    val StatusThinking = Color(0xFFE0E0E0)
    val StatusSpeaking = Color(0xFFF0F0F0)

    object Dark {
        val Background = Color(0xFF000000)
        val Surface = Color(0xFF0A0A0A)
        val SurfaceHigh = Color(0xFF111111)
        val GlassFill = Color(0x0DFFFFFF)
        val GlassFillStrong = Color(0x14FFFFFF)
        val GlassBorder = Color(0x33FFFFFF)
        val GlassBorderSoft = Color(0x0DFFFFFF)
        val TextPrimary = Color(0xFFF8FAFC)
        val TextSecondary = Color(0xFF94A3B8)
        val TextTertiary = Color(0xFF475569)
        val GlowPrimary = Color(0x26F2F2F2)
        val GlowSecondary = Color(0x26C8C8C8)
        val UserBubble = Color(0x33F2F2F2)
        val AiBubble = Color(0x14FFFFFF)
    }

    object Light {
        val Background = Color(0xFFF0F2F5)
        val Surface = Color(0xFFFFFFFF)
        val SurfaceHigh = Color(0xFFF8FAFC)
        val GlassFill = Color(0xB3FFFFFF)
        val GlassFillStrong = Color(0xE6FFFFFF)
        val GlassBorder = Color(0xCCFFFFFF)
        val GlassBorderSoft = Color(0x40FFFFFF)
        val TextPrimary = Color(0xFF0F172A)
        val TextSecondary = Color(0xFF475569)
        val TextTertiary = Color(0xFF94A3B8)
        val GlowPrimary = Color(0x330F0F0F)
        val GlowSecondary = Color(0x33141414)
        val UserBubble = Color(0x1F0F0F0F)
        val AiBubble = Color(0xD9FFFFFF)
    }

    fun textPrimary(isDark: Boolean) = if (isDark) Dark.TextPrimary else Light.TextPrimary
    fun textSecondary(isDark: Boolean) = if (isDark) Dark.TextSecondary else Light.TextSecondary
    fun textTertiary(isDark: Boolean) = if (isDark) Dark.TextTertiary else Light.TextTertiary
    fun background(isDark: Boolean) = if (isDark) Dark.Background else Light.Background
    fun surface(isDark: Boolean) = if (isDark) Dark.Surface else Light.Surface
    fun glassFill(isDark: Boolean) = if (isDark) Dark.GlassFill else Light.GlassFill
    fun glassFillStrong(isDark: Boolean) = if (isDark) Dark.GlassFillStrong else Light.GlassFillStrong
    fun glassBorder(isDark: Boolean) = if (isDark) Dark.GlassBorder else Light.GlassBorder
    fun userBubble(isDark: Boolean) = if (isDark) Dark.UserBubble else Light.UserBubble
    fun aiBubble(isDark: Boolean) = if (isDark) Dark.AiBubble else Light.AiBubble

    fun statusColor(status: String): Color = when {
        status.contains("Listening", ignoreCase = true) -> StatusListening
        status.contains("Thinking", ignoreCase = true) || status.contains("Processing", ignoreCase = true) -> StatusThinking
        status.contains("Speaking", ignoreCase = true) -> StatusSpeaking
        status.contains("Error", ignoreCase = true) || status.contains("Failed", ignoreCase = true) -> Red
        else -> EmeraldBright
    }

    val AmbientGlow: Brush
        get() = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
            radius = 900f
        )

    val EmeraldGradient: Brush
        get() = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC8C8C8)))

    val BrandGradient: Brush
        get() = Brush.linearGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFDCDCDC), Color(0xFFB8B8B8))
        )

    val BorderSheen: Brush
        get() = Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.05f)
            )
        )

    fun sheenBorder(isDark: Boolean): Brush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.22f else 0.85f),
            Color.White.copy(alpha = if (isDark) 0.14f else 0.45f),
            Color.White.copy(alpha = if (isDark) 0.05f else 0.4f)
        )
    )

    fun inputBarFill(isDark: Boolean): Brush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF121212)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F2)))
    }

    fun codeBlockFill(): Brush = Brush.verticalGradient(
        listOf(Color(0xFF161616), Color(0xFF101010))
    )

    fun codeHeaderFill(): Brush = Brush.verticalGradient(
        listOf(Color(0xFF1E1E1E), Color(0xFF171717))
    )

    fun voiceBackdrop(): Brush = Brush.verticalGradient(
        listOf(Color(0xFF050505), Color(0xFF0A0A0A), Color(0xFF000000))
    )
}
