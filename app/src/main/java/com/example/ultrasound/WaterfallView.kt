package com.example.ultrasound

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Scrolling spectrogram: frequency on the x axis, newest row at the top.
 * [configure] and [addRow] may be called from any thread.
 */
class WaterfallView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val historyRows = 400
    private val lock = Any()

    private var cols = 0
    private var minHz = 0f
    private var maxHz = 0f
    private var pixels = IntArray(0)
    private var head = 0          // Index of the newest row in the ring.
    private var pendingRows = 0   // Rows written to [pixels] but not yet copied to [bitmap].
    private var bitmap: Bitmap? = null
    private var floorDb = -100f   // Adaptive noise floor for colour scaling.

    /** Frequency (Hz) that is shifted down to 0 Hz while listening; ≤0 hides the marker. */
    var markerHz = 0f
        set(value) { field = value; invalidate() }

    private val palette = buildPalette()
    private val density = resources.displayMetrics.density
    private val axisHeight = 28 * density

    private val textColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).run {
        getColor(0, Color.GRAY).also { recycle() }
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
        strokeWidth = density
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hearingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        strokeWidth = 2 * density
        textSize = 12 * density
    }
    private val src = Rect()
    private val dst = RectF()

    fun configure(sampleRate: Int, minHz: Float, maxHz: Float, cols: Int) {
        synchronized(lock) {
            this.cols = cols
            this.minHz = minHz
            this.maxHz = maxHz
            pixels = IntArray(cols * historyRows) { palette[0] }
            head = 0
            pendingRows = historyRows
            bitmap = Bitmap.createBitmap(cols, historyRows, Bitmap.Config.ARGB_8888)
        }
        postInvalidate()
    }

    fun addRow(db: FloatArray) {
        synchronized(lock) {
            if (cols == 0) return
            var mean = 0f
            for (i in 0 until cols) mean += db[i]
            mean /= cols
            floorDb += (mean - floorDb) * 0.02f
            val lo = floorDb - 5f
            val range = 55f
            head = if (head == 0) historyRows - 1 else head - 1
            val offset = head * cols
            for (i in 0 until cols) {
                val v = ((db[i] - lo) / range * 255f).toInt().coerceIn(0, 255)
                pixels[offset + i] = palette[v]
            }
            if (pendingRows < historyRows) pendingRows++
        }
        postInvalidateOnAnimation()
    }

    fun xForHz(hz: Float): Float = if (maxHz <= minHz) 0f else (hz - minHz) / (maxHz - minHz) * width

    override fun onDraw(canvas: Canvas) {
        val plotH = height - axisHeight
        synchronized(lock) {
            if (cols == 0) return
            val bmp = bitmap ?: return
            // Copy only rows that changed since the last frame.
            var r = head
            repeat(pendingRows) {
                bmp.setPixels(pixels, r * cols, cols, 0, r, cols, 1)
                r = (r + 1) % historyRows
            }
            pendingRows = 0

            // The ring holds newest at [head]; draw head..end, then 0..head below it.
            val rowH = plotH / historyRows
            val firstPart = historyRows - head
            src.set(0, head, cols, historyRows)
            dst.set(0f, 0f, width.toFloat(), firstPart * rowH)
            canvas.drawBitmap(bmp, src, dst, bitmapPaint)
            if (head > 0) {
                src.set(0, 0, cols, head)
                dst.set(0f, firstPart * rowH, width.toFloat(), plotH)
                canvas.drawBitmap(bmp, src, dst, bitmapPaint)
            }
        }

        // 20 kHz: upper limit of human hearing.
        if (20_000f in minHz..maxHz) {
            val x = xForHz(20_000f)
            canvas.drawLine(x, 0f, x, plotH, hearingPaint)
        }
        if (markerHz > minHz && markerHz < maxHz) {
            val x = xForHz(markerHz)
            canvas.drawLine(x, 0f, x, plotH, markerPaint)
            markerPaint.textAlign = if (x > width / 2) Paint.Align.RIGHT else Paint.Align.LEFT
            val pad = if (x > width / 2) -4 * density else 4 * density
            canvas.drawText("0 Hz heard", x + pad, 14 * density, markerPaint)
        }

        // Frequency axis.
        val span = maxHz - minHz
        val stepHz = when {
            span > 60_000 -> 10_000
            span > 20_000 -> 5_000
            else -> 2_000
        }
        var f = ((minHz / stepHz).toInt() + 1) * stepHz
        val baseline = plotH + axisHeight - 8 * density
        while (f < maxHz) {
            val x = xForHz(f.toFloat())
            canvas.drawLine(x, plotH, x, plotH + 4 * density, axisPaint)
            canvas.drawText("${f / 1000}k", x, baseline, axisPaint)
            f += stepHz
        }
    }

    private fun buildPalette(): IntArray {
        // Approximation of the "inferno" colour map.
        val stops = arrayOf(
            0.00f to 0x000004, 0.15f to 0x1F0C48, 0.30f to 0x550F6D, 0.45f to 0x88226A,
            0.60f to 0xBA3655, 0.75f to 0xE55C30, 0.88f to 0xFB9B06, 1.00f to 0xFCFFA4,
        )
        return IntArray(256) { i ->
            val t = i / 255f
            val k = stops.indexOfLast { it.first <= t }.coerceAtMost(stops.size - 2)
            val (t0, c0) = stops[k]
            val (t1, c1) = stops[k + 1]
            val u = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * u).roundToInt()
            Color.rgb(mix(16), mix(8), mix(0))
        }
    }
}
