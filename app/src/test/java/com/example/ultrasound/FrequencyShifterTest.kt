package com.example.ultrasound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class FrequencyShifterTest {

    private fun spectrumDb(x: FloatArray): FloatArray {
        val n = x.size
        val re = x.copyOf()
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        return FloatArray(n / 2) { (20 * log10(hypot(re[it], im[it]) + 1e-9f)) }
    }

    @Test
    fun shiftsUltrasoundDownWithoutImages() {
        val rate = 192_000
        val n = 16_384
        val shifter = FrequencyShifter(rate.toFloat()).apply { setShift(25_000f) }
        val out = FloatArray(n)
        // Warm up the filter, then capture.
        var t = 0
        repeat(2) {
            for (i in 0 until n) {
                out[i] = shifter.process(sin(2 * PI * 30_000.0 * t / rate).toFloat())
                t++
            }
        }
        val spec = spectrumDb(out.mapIndexed { i, v -> v * (0.5f - 0.5f * kotlin.math.cos(2 * PI * i / n).toFloat()) }.toFloatArray())
        val binHz = rate.toFloat() / n
        val peak = spec.indices.maxBy { spec[it] }
        assertEquals(5_000f, peak * binHz, binHz * 2)
        // Sum image (55 kHz) and anything else should be far below the wanted tone.
        val other = spec.indices.filter { kotlin.math.abs(it - peak) > 10 }.maxOf { spec[it] }
        assertTrue("image too strong: ${spec[peak] - other} dB", spec[peak] - other > 40)
    }

    @Test
    fun fftFindsTone() {
        val n = 1024
        val x = FloatArray(n) { sin(2 * PI * 64 * it / n).toFloat() }
        val spec = spectrumDb(x)
        assertEquals(64, spec.indices.maxBy { spec[it] })
    }
}
