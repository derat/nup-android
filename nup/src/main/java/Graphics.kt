/**
 * Copyright 2022 Daniel Erat.
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.math.MathUtils

/** Compute the brightness of the specified region of [bitmap]. */
suspend fun computeBrightness(
    bitmap: Bitmap?,
    left: Double = 0.0,
    top: Double = 0.0,
    right: Double = 1.0,
    bottom: Double = 1.0,
): Brightness? {
    bitmap ?: return null
    val width = bitmap.getWidth()
    val height = bitmap.getHeight()

    val x = (MathUtils.clamp(left, 0.0, 1.0) * width).toInt()
    val y = (MathUtils.clamp(top, 0.0, 1.0) * height).toInt()
    val w = MathUtils.clamp(((right - left) * width).toInt(), 0, width - x)
    val h = MathUtils.clamp(((bottom - top) * height).toInt(), 0, height - y)
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, x, y, w, h)

    // Count the number of light-colored pixels and compute the mean and standard deviation
    // of pixels' luminosities: https://www.strchr.com/standard_deviation_in_one_pass
    var lc = 0; var n = 0
    var sum = 0.0; var sqSum = 0.0
    for (p in pixels) {
        if (Color.alpha(p) == 0) continue
        val lum = Color.luminance(p)
        if (lum >= lightThreshold) lc++
        sum += lum
        sqSum += lum * lum
        n++
    }
    if (n == 0) return null

    val lightPct = lc.toDouble() / n
    val mean = sum / n
    val stdDev = Math.sqrt(sqSum / n - mean * mean)

    // TODO: I'm not really happy with this approach. It might be better to use Palette
    // (https://developer.android.com/reference/kotlin/androidx/palette/graphics/Palette) to extract
    // the dominant color from the region, and then choose white or black text based on its contrast
    // against that color (see e.g. https://ux.stackexchange.com/a/107319). Palette seemed pretty
    // unreliable when I tried it, though, often just falling back to the passed-in default color.
    // Perhaps the average color should be used as the default, but that may produce bad results for
    // e.g. a mix of pure white and black pixels.
    //
    // Alternately, since using the standard deviation to determine busy-ness also doesn't work that
    // well, maybe it'd be best to check white and black's contrast against each pixel's luminosity,
    // and then use just those numbers to determine how to classify the bitmap.
    val light = lightPct >= 0.5
    val busy = stdDev >= busyThreshold
    return when {
        light && busy -> Brightness.LIGHT_BUSY
        !light && busy -> Brightness.DARK_BUSY
        light -> Brightness.LIGHT
        else -> Brightness.DARK
    }
}

val lightThreshold = 0.5 // min luminosity for pixel to be bright
val busyThreshold = 0.1 // min luminosity std dev for bitmap to be busy

enum class Brightness {
    LIGHT,
    DARK,
    LIGHT_BUSY,
    DARK_BUSY,
}
