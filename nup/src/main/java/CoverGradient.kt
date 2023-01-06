/**
 * Copyright 2022 Daniel Erat <dan@erat.org>.
 * All rights reserved.
 */

package org.erat.nup

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader.TileMode
import android.graphics.drawable.Drawable

/**
 * Draws a translucent gradient to display over cover images to make text more readable.
 *
 * The gradient starts at [startColor] in its bottom-left corner and fades to be completely
 * transparent to the top and right. The drawable should be sized to match the text, but the
 * gradient extends beyond the canvas's top and right edges, so the parent view should set
 * android:clipChildren="false".
 */
class CoverGradient(private val startColor: Int) : Drawable() {
    private var colors: IntArray
    private var stops: FloatArray

    override fun draw(canvas: Canvas) {
        val width = canvas.getWidth().toFloat()
        val height = canvas.getHeight().toFloat()
        val size = 3 * height

        val paint = Paint()
        paint.setDither(true)

        val rleft = width - height
        val rgrad = RadialGradient(rleft, height, size, colors, stops, TileMode.CLAMP)
        paint.setShader(rgrad)
        canvas.drawRect(rleft, height - size, rleft + size, height, paint)

        if (rleft > 0f) {
            val lgrad = LinearGradient(0f, height, 0f, height - size, colors, stops, TileMode.CLAMP)
            paint.setShader(lgrad)
            canvas.drawRect(0f, height - size, rleft, height, paint)
        }
    }

    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}

    init {
        val scale = { a: Float ->
            Color.argb(
                Math.round(Color.alpha(startColor).toFloat() * a).toInt(),
                Color.red(startColor), Color.green(startColor), Color.blue(startColor)
            )
        }

        // These values come from https://larsenwork.com/easing-gradients/.
        // See also https://ishadeed.com/article/handling-text-over-image-css/.
        colors = intArrayOf(
            scale(1.0f),
            scale(0.987f),
            scale(0.951f),
            scale(0.896f),
            scale(0.825f),
            scale(0.741f),
            scale(0.648f),
            scale(0.55f),
            scale(0.45f),
            scale(0.352f),
            scale(0.259f),
            scale(0.175f),
            scale(0.104f),
            scale(0.049f),
            scale(0.013f),
            scale(0f),
        )
        stops = floatArrayOf(
            0f,
            0.081f,
            0.155f,
            0.225f,
            0.29f,
            0.353f,
            0.412f,
            0.471f,
            0.529f,
            0.588f,
            0.647f,
            0.71f,
            0.775f,
            0.845f,
            0.919f,
            1f,
        )
    }
}
