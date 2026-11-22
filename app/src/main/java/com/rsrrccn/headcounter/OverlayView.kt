package com.rsrrccn.headcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws the head boxes on top of the camera preview. */
class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var heads: List<HeadCounter.Tracked> = emptyList()
    private var imgW = 1
    private var imgH = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun setHeads(newHeads: List<HeadCounter.Tracked>, imageWidth: Int, imageHeight: Int) {
        heads = newHeads
        imgW = imageWidth
        imgH = imageHeight
        invalidate()
    }

    fun clear() {
        heads = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The preview fills the screen and crops the edges (FILL_CENTER), so map the same way
        val scale = max(width / imgW.toFloat(), height / imgH.toFloat())
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f

        for (h in heads) {
            val r = RectF(
                h.head.left * scale + dx, h.head.top * scale + dy,
                h.head.right * scale + dx, h.head.bottom * scale + dy
            )
            boxPaint.color = when {
                h.personNumber != null -> Color.GREEN   // counted
                h.scanning -> Color.YELLOW              // confirming
                else -> Color.CYAN                      // preview only
            }
            canvas.drawRect(r, boxPaint)
            if (h.personNumber != null) {
                canvas.drawText("#${h.personNumber}", r.left + 8f, r.top - 12f, textPaint)
            }
        }
    }
}
