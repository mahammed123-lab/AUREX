package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object AurexMotion {

    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val DecelerateEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val SoftEasing: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    const val DurationFast = 180
    const val DurationNormal = 280
    const val DurationSlow = 420
    const val DurationExpressive = 560

    fun <T> fast() = tween<T>(DurationFast, easing = LinearOutSlowInEasing)
    fun <T> normal() = tween<T>(DurationNormal, easing = EmphasizedEasing)
    fun <T> slow() = tween<T>(DurationSlow, easing = EmphasizedEasing)
    fun <T> expressive() = tween<T>(DurationExpressive, easing = DecelerateEasing)

    fun <T> bouncySpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    fun <T> smoothSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> snappySpring() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 380f
    )

    fun <T> entranceSpring() = spring<T>(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = null
    )

    fun <T> popSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> gentleSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 240f
    )

    fun stagger(index: Int, stepMs: Int = 45, baseMs: Int = 0): Int =
        (baseMs + index * stepMs).coerceAtMost(600)
}
