package com.example.ultrasound

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), AudioEngine.Listener {

    private lateinit var waterfall: WaterfallView
    private lateinit var peakText: TextView
    private lateinit var infoText: TextView
    private lateinit var listenSwitch: Switch
    private lateinit var shiftLabel: TextView
    private lateinit var shiftSeek: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var volumeSeek: SeekBar
    private lateinit var engine: AudioEngine

    /** Shift slider resolution. */
    private val shiftStepHz = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Edge-to-edge: pad content away from the system bars.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(v.paddingLeft.coerceAtLeast(bars.left), bars.top, v.paddingRight.coerceAtLeast(bars.right), bars.bottom)
            insets
        }

        waterfall = findViewById(R.id.waterfall)
        peakText = findViewById(R.id.peakText)
        infoText = findViewById(R.id.infoText)
        listenSwitch = findViewById(R.id.listenSwitch)
        shiftLabel = findViewById(R.id.shiftLabel)
        shiftSeek = findViewById(R.id.shiftSeek)
        volumeLabel = findViewById(R.id.volumeLabel)
        volumeSeek = findViewById(R.id.volumeSeek)

        engine = AudioEngine(this, waterfall, this)

        listenSwitch.setOnCheckedChangeListener { _, checked ->
            engine.listening = checked
            updateMarker()
        }
        shiftSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                engine.shiftHz = progress * shiftStepHz.toFloat()
                shiftLabel.text = getString(R.string.shift_label, engine.shiftHz / 1000f)
                updateMarker()
            }
        })
        volumeSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                engine.gainDb = progress.toFloat()
                volumeLabel.text = getString(R.string.volume_label, progress)
            }
        })
        volumeLabel.text = getString(R.string.volume_label, volumeSeek.progress)
        engine.gainDb = volumeSeek.progress.toFloat()
        shiftLabel.text = getString(R.string.shift_label, engine.shiftHz / 1000f)
        peakText.setOnClickListener { if (!hasMicPermission()) requestMic() }
    }

    override fun onStart() {
        super.onStart()
        if (hasMicPermission()) engine.start() else requestMic()
    }

    override fun onStop() {
        engine.stop()
        super.onStop()
    }

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMic() = requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) engine.start()
        else peakText.setText(R.string.permission_needed)
    }

    private fun updateMarker() {
        waterfall.markerHz = if (listenSwitch.isChecked) engine.shiftHz else 0f
    }

    // ---- AudioEngine.Listener (audio thread) ----

    override fun onStarted(sampleRate: Int) = runOnUiThread {
        val nyquist = sampleRate / 2
        infoText.text = getString(R.string.mic_info, sampleRate / 1000, if (nyquist > 30_000) 15 else 10, nyquist / 1000)
        if (nyquist <= 20_000) peakText.text = getString(R.string.no_ultrasound_rate, sampleRate / 1000)
        // Allow shifting anywhere up to just below Nyquist; keep the current value if it still fits.
        val wanted = engine.shiftHz.toInt()
        shiftSeek.max = (nyquist - 500) / shiftStepHz
        shiftSeek.progress = (wanted.coerceAtMost(nyquist - 2_000)) / shiftStepHz
    }

    override fun onPeak(freqHz: Float?, snrDb: Float) = runOnUiThread {
        peakText.text = if (freqHz != null) getString(R.string.peak_detected, freqHz / 1000f, snrDb)
        else getString(R.string.peak_none)
    }

    override fun onError(message: String) = runOnUiThread {
        peakText.text = message
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    private companion object {
        const val REQ_MIC = 1
    }
}
