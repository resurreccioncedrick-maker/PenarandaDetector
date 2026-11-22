package com.rsrrccn.headcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws the head boxes on top of the camera preview. */
class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var heads: List<HeadCounter.Tracked> = emptyList()
    private var imgW = 1
    private var imgH = 1
    private val d = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        textSize = 10f * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
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
        val radius = 5f * d

        for (h in heads) {
            val r = RectF(
                h.body.left * scale + dx, h.body.top * scale + dy,
                h.body.right * scale + dx, h.body.bottom * scale + dy
            )
            val color = when {
                h.personNumber != null -> COUNTED
                h.scanning -> CONFIRMING
                else -> PREVIEW
            }
            boxPaint.color = color
            canvas.drawRoundRect(r, radius, radius, boxPaint)
            chipPaint.color = color
            canvas.drawCircle(h.head.centerX() * scale + dx, h.head.centerY() * scale + dy, 3f * d, chipPaint)

            if (h.personNumber != null) {
                val label = h.personNumber.toString()
                val cw = max(16f * d, textPaint.measureText(label) + 8f * d)
                val ch = 14f * d
                val chip = RectF(r.centerX() - cw / 2f, r.top - ch - 2f * d, r.centerX() + cw / 2f, r.top - 2f * d)
                chipPaint.color = color
                canvas.drawRoundRect(chip, ch / 2f, ch / 2f, chipPaint)
                canvas.drawText(label, chip.centerX(), chip.centerY() + textPaint.textSize / 3f, textPaint)
            }
        }
    }

    companion object {
        val COUNTED: Int = Color.parseColor("#22D3A6")     // green  = counted
        val CONFIRMING: Int = Color.parseColor("#FBBF24")  // amber  = confirming
        val PREVIEW: Int = Color.parseColor("#E2E8F0")     // white  = just looking
    }
}
