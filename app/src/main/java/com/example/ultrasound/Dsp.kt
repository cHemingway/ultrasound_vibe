package com.example.ultrasound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** RBJ cookbook biquad (transposed direct form II). */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun setLowPass(sampleRate: Float, freq: Float, q: Float) = design(sampleRate, freq, q, highPass = false)
    fun setHighPass(sampleRate: Float, freq: Float, q: Float) = design(sampleRate, freq, q, highPass = true)

    private fun design(sampleRate: Float, freq: Float, q: Float, highPass: Boolean) {
        val w0 = 2.0 * PI * freq / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        val bb0 = if (highPass) (1.0 + c) / 2.0 else (1.0 - c) / 2.0
        val bb1 = if (highPass) -(1.0 + c) else 1.0 - c
        b0 = (bb0 / a0).toFloat()
        b1 = (bb1 / a0).toFloat()
        b2 = (bb0 / a0).toFloat()
        a1 = (-2.0 * c / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** Cascade of biquads forming an even-order Butterworth filter. */
class Butterworth(order: Int) {
    private val qs = FloatArray(order / 2) { k ->
        (1.0 / (2.0 * sin(PI * (2 * k + 1) / (2.0 * order)))).toFloat()
    }
    private val stages = Array(order / 2) { Biquad() }

    fun setLowPass(sampleRate: Float, freq: Float) =
        stages.forEachIndexed { i, s -> s.setLowPass(sampleRate, freq, qs[i]) }

    fun setHighPass(sampleRate: Float, freq: Float) =
        stages.forEachIndexed { i, s -> s.setHighPass(sampleRate, freq, qs[i]) }

    fun process(x: Float): Float {
        var y = x
        for (s in stages) y = s.process(y)
        return y
    }
}

/**
 * Single-sideband frequency shifter: every input frequency f is moved to f - shift.
 * Uses a Hilbert FIR to build the analytic signal, then multiplies by e^(-j·2π·shift·t).
 * Unlike a plain heterodyne mixer this produces no sum-frequency image, so nothing aliases back.
 */
class FrequencyShifter(private val sampleRate: Float) {
    private val taps = 127
    private val center = taps / 2
    // Only odd offsets from the centre are non-zero for an ideal Hilbert transformer.
    private val oddOffsets: IntArray
    private val oddCoeffs: FloatArray
    private val line = FloatArray(taps * 2)
    private var pos = 0

    private var cosPh = 1.0
    private var sinPh = 0.0
    private var stepCos = 1.0
    private var stepSin = 0.0
    private var renorm = 0

    init {
        val offs = ArrayList<Int>()
        val coeffs = ArrayList<Float>()
        for (n in 0 until taps) {
            val k = n - center
            if (k % 2 != 0) {
                val window = 0.42 - 0.5 * cos(2.0 * PI * n / (taps - 1)) + 0.08 * cos(4.0 * PI * n / (taps - 1))
                offs += n
                coeffs += (2.0 / (PI * k) * window).toFloat()
            }
        }
        oddOffsets = offs.toIntArray()
        oddCoeffs = coeffs.toFloatArray()
    }

    fun setShift(hz: Float) {
        val w = 2.0 * PI * hz / sampleRate
        stepCos = cos(w)
        stepSin = sin(w)
    }

    fun process(x: Float): Float {
        // Write into a doubled delay line so the taps are always a contiguous window.
        line[pos] = x
        line[pos + taps] = x
        pos = if (pos + 1 == taps) 0 else pos + 1
        // Window line[pos .. pos+taps-1] holds oldest .. newest; FIR index n maps to newest - n.
        val newest = pos + taps - 1
        val i = line[newest - center]
        var q = 0f
        for (j in oddOffsets.indices) q += oddCoeffs[j] * line[newest - oddOffsets[j]]

        // Re{(I + jQ)·e^(-jφ)} = I·cosφ + Q·sinφ
        val y = (i * cosPh + q * sinPh).toFloat()

        val c = cosPh * stepCos - sinPh * stepSin
        sinPh = sinPh * stepCos + cosPh * stepSin
        cosPh = c
        if (++renorm >= 4096) {
            renorm = 0
            val mag = kotlin.math.sqrt(cosPh * cosPh + sinPh * sinPh)
            cosPh /= mag; sinPh /= mag
        }
        return y
    }
}
