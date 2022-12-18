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
 * The gradient fills the canvas and fades to be fully transparent at its top and right edges.
 * Android unfortunately doesn't seem to be able to do something like this via its built-in
 * gradients -- radial gradients seem to only support circles, not arbitrary ellipses.
 */
class CoverGradient(private val startColor: Int) : Drawable() {
    private val start = Color.pack(startColor)
    private val end = scaleAlpha(start, 0f)

    override fun draw(canvas: Canvas) {
        val width = canvas.getWidth().toFloat()
        val height = canvas.getHeight().toFloat()
        val colors = longArrayOf(start, end)

        val paint = Paint()
        paint.setDither(true)

        // TODO: I'm still not happy with how this looks.
        val rleft = width - height
        val rgrad = RadialGradient(rleft, height, height, colors, null, TileMode.CLAMP)
        paint.setShader(rgrad)
        canvas.drawRect(rleft, 0f, width, height, paint)

        if (rleft > 0f) {
            val lgrad = LinearGradient(0f, height, 0f, 0f, colors, null, TileMode.CLAMP)
            paint.setShader(lgrad)
            canvas.drawRect(0f, 0f, rleft, height, paint)
        }
    }

    private fun scaleAlpha(color: Long, scale: Float): Long = Color.pack(
        Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color) * scale
    )

    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
}
