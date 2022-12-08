/**
 * Copyright 2022 Daniel Erat.
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.math.MathUtils

/** Get the average color from specified region of [bitmap]. */
suspend fun getAverageColor(
    bitmap: Bitmap?,
    left: Double = 0.0,
    top: Double = 0.0,
    right: Double = 1.0,
    bottom: Double = 1.0,
): Int? {
    bitmap ?: return null
    val width = bitmap.getWidth()
    val height = bitmap.getHeight()

    val x = (MathUtils.clamp(left, 0.0, 1.0) * width).toInt()
    val y = (MathUtils.clamp(top, 0.0, 1.0) * height).toInt()
    val w = MathUtils.clamp(((right - left) * width).toInt(), 0, width - x)
    val h = MathUtils.clamp(((bottom - top) * height).toInt(), 0, height - y)
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, x, y, w, h)

    var r = 0L; var g = 0L; var b = 0L; var n = 0L
    for (p in pixels) {
        if (Color.alpha(p) == 0) continue
        r += Color.red(p)
        g += Color.green(p)
        b += Color.blue(p)
        n++
    }
    if (n == 0L) return null
    return Color.argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}
