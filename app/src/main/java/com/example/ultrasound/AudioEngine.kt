package com.example.ultrasound

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Captures the microphone at the highest sample rate available, feeds FFT rows to the waterfall
 * and (optionally) plays a pitch-shifted copy to the current media output, e.g. Bluetooth headphones.
 */
class AudioEngine(
    private val context: Context,
    private val waterfall: WaterfallView,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the audio thread once the microphone is open. */
        fun onStarted(sampleRate: Int)
        /** Strongest ultrasonic component, or null if nothing stands out. Called on the audio thread. */
        fun onPeak(freqHz: Float?, snrDb: Float)
        fun onError(message: String)
    }

    @Volatile var shiftHz = 20_000f
    @Volatile var gainDb = 20f
    @Volatile var listening = false

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "ultrasound-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission") // Checked by MainActivity before start().
    private fun openRecorder(): AudioRecord? {
        val am = context.getSystemService(AudioManager::class.java)
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION // Least-processed fallback.
        }
        val builtInMic = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        for (rate in intArrayOf(192_000, 96_000, 48_000, 44_100)) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBuf <= 0) continue
            val rec = try {
                AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minBuf, rate / 5 * 4))
            } catch (e: Exception) {
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            // Keep recording from the phone's mic even when a Bluetooth headset is connected.
            builtInMic?.let { rec.preferredDevice = it }
            return rec
        }
        return null
    }

    private fun openTrack(rate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, rate / 10 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun run() {
        val rec = openRecorder()
        if (rec == null) {
            listener.onError("Could not open the microphone")
            running = false
            return
        }
        val rate = rec.sampleRate
        val nyquist = rate / 2f

        // ~50 Hz bins, ~60 waterfall rows per second.
        var fftSize = 256
        while (fftSize < rate / 50) fftSize *= 2
        val hop = rate / 60
        val fft = Fft(fftSize)
        val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
        val ring = FloatArray(fftSize)
        var ringPos = 0
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val binHz = rate.toFloat() / fftSize
        val minDisplayHz = if (nyquist > 30_000f) 15_000f else 10_000f
        val firstBin = (minDisplayHz / binHz).toInt()
        val bins = fftSize / 2 - firstBin
        val row = FloatArray(bins)
        val ultrasonicStart = max(0, ((20_000f / binHz).toInt() - firstBin))
        val scratch = FloatArray(bins)
        // Full-scale sine through a Hann window gives |X| = N/4.
        val refMag = fftSize / 4f

        waterfall.configure(rate, firstBin * binHz, nyquist, bins)
        listener.onStarted(rate)

        // Playback path: decimate to ≤48 kHz when it divides evenly.
        val decim = if (rate % 48_000 == 0) rate / 48_000 else if (rate % 44_100 == 0) rate / 44_100 else 1
        val outRate = rate / decim
        val track = openTrack(outRate)
        val shifter = FrequencyShifter(rate.toFloat())
        val preHp = Butterworth(4)
        val postLp = Butterworth(6).apply { setLowPass(rate.toFloat(), min(15_000f, outRate * 0.4f)) }
        var currentShift = -1f
        val inBuf = FloatArray(hop)
        val outBuf = FloatArray(hop / decim + 1)
        var decimCount = 0
        var wasListening = false
        var rowCount = 0

        try {
            rec.startRecording()
            track.play()
            while (running) {
                val n = rec.read(inBuf, 0, hop, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) { listener.onError("Microphone read error ($n)"); break }
                    continue
                }

                // ---- Pitch-shifted monitor ----
                val listen = listening
                if (listen) {
                    val shift = min(shiftHz, nyquist - 500f)
                    if (shift != currentShift) {
                        currentShift = shift
                        shifter.setShift(shift)
                        // Remove everything below the shift so it doesn't fold back as mirror noise.
                        preHp.setHighPass(rate.toFloat(), max(shift, 20f))
                    }
                    val gain = Math.pow(10.0, gainDb / 20.0).toFloat()
                    var outN = 0
                    for (i in 0 until n) {
                        val y = postLp.process(shifter.process(preHp.process(inBuf[i])))
                        if (++decimCount >= decim) {
                            decimCount = 0
                            outBuf[outN++] = tanh(y * gain) // Soft limiter protects ears.
                        }
                    }
                    track.write(outBuf, 0, outN, AudioTrack.WRITE_NON_BLOCKING)
                } else if (wasListening) {
                    track.pause(); track.flush(); track.play()
                }
                wasListening = listen

                // ---- Spectrum ----
                for (i in 0 until n) {
                    ring[ringPos] = inBuf[i]
                    ringPos = if (ringPos + 1 == fftSize) 0 else ringPos + 1
                }
                for (i in 0 until fftSize) {
                    re[i] = ring[(ringPos + i) % fftSize] * window[i]
                    im[i] = 0f
                }
                fft.transform(re, im)
                for (b in 0 until bins) {
                    val k = b + firstBin
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / refMag
                    row[b] = (20.0 * log10(mag + 1e-12)).toFloat()
                }
                waterfall.addRow(row)

                // ---- Detection (~6 Hz) ----
                if (++rowCount % 10 == 0 && ultrasonicStart < bins) {
                    var peakBin = ultrasonicStart
                    for (b in ultrasonicStart until bins) if (row[b] > row[peakBin]) peakBin = b
                    val count = bins - ultrasonicStart
                    System.arraycopy(row, ultrasonicStart, scratch, 0, count)
                    java.util.Arrays.sort(scratch, 0, count)
                    val snr = row[peakBin] - scratch[count / 2]
                    // Ignore the top few bins: anti-alias filter roll-off makes them noisy.
                    val usable = peakBin < bins - bins / 50
                    if (snr > 15f && usable) listener.onPeak((peakBin + firstBin) * binHz, snr)
                    else listener.onPeak(null, snr)
                }
            }
        } catch (e: Exception) {
            listener.onError(e.message ?: e.toString())
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { track.stop() } catch (_: Exception) {}
            track.release()
            running = false
        }
    }
}
