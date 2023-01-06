/**
 * Copyright 2022 Daniel Erat.
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.core.math.MathUtils

/** Compute the brightness of the specified region of [bitmap]. */
suspend fun computeBrightness(
    bitmap: Bitmap?,
    left: Float = 0f,
    top: Float = 0f,
    right: Float = 1f,
    bottom: Float = 1f,
): Brightness? {
    bitmap ?: return null
    val width = bitmap.getWidth()
    val height = bitmap.getHeight()

    val x = (MathUtils.clamp(left, 0f, 1f) * width).toInt()
    val y = (MathUtils.clamp(top, 0f, 1f) * height).toInt()
    val w = MathUtils.clamp(((right - left) * width).toInt(), 0, width - x)
    val h = MathUtils.clamp(((bottom - top) * height).toInt(), 0, height - y)
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, x, y, w, h)

    // Count pixels with high contrast against white/black text.
    var white = 0f; var black = 0f; var n = 0f
    for (p in pixels) {
        if (Color.alpha(p) == 0) continue
        val lum = getLuminance(p)
        if (getContrastRatio(lum, whiteLum) > minWhiteContrast) white++
        if (getContrastRatio(lum, blackLum) > minBlackContrast) black++
        n++
    }
    if (n == 0f) return null

    val dark = white >= black
    val busy = 1f - Math.max(white, black) / n >= busyThreshold
    return when {
        dark && busy -> Brightness.DARK_BUSY
        dark -> Brightness.DARK
        busy -> Brightness.LIGHT_BUSY
        else -> Brightness.LIGHT
    }
}

fun getLuminance(color: Int): Float =
    if (Build.VERSION.SDK_INT >= 24) Color.luminance(color)
    else ColorUtils.calculateLuminance(color).toFloat()

fun getContrastRatio(lum1: Float, lum2: Float) =
    (Math.max(lum1, lum2) + 0.05f) / (Math.min(lum1, lum2) + 0.05f)

val whiteLum = getLuminance(Color.WHITE)
val blackLum = getLuminance(Color.BLACK)
val minWhiteContrast = 4f // min contrast against white text
val minBlackContrast = 12f // min contrast against black text
val busyThreshold = 0.05f // busy if this fraction or more are low-contrast

enum class Brightness {
    LIGHT,
    DARK,
    LIGHT_BUSY,
    DARK_BUSY,
}
