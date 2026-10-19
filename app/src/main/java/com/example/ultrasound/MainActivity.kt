package com.example.ultrasound

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
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
    private lateinit var floorLabel: TextView
    private lateinit var floorSeek: SeekBar
    private lateinit var rangeLabel: TextView
    private lateinit var rangeSeek: SeekBar
    private lateinit var engine: AudioEngine
    private lateinit var prefs: SharedPreferences

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
        floorLabel = findViewById(R.id.floorLabel)
        floorSeek = findViewById(R.id.floorSeek)
        rangeLabel = findViewById(R.id.rangeLabel)
        rangeSeek = findViewById(R.id.rangeSeek)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        engine = AudioEngine(this, waterfall, this)

        listenSwitch.setOnCheckedChangeListener { _, checked ->
            engine.listening = checked
            updateMarker()
        }
        shiftSeek.max = 1000 // Real limit is set once the sample rate is known.
        shiftSeek.progress = (prefs.getFloat(KEY_SHIFT, 20_000f) / shiftStepHz).toInt()
        shiftSeek.bind { progress ->
            engine.shiftHz = progress * shiftStepHz.toFloat()
            shiftLabel.text = getString(R.string.shift_label, engine.shiftHz / 1000f)
            updateMarker()
        }
        volumeSeek.bind(KEY_VOLUME, 20) { progress ->
            engine.gainDb = progress.toFloat()
            volumeLabel.text = getString(R.string.volume_label, progress)
        }
        // Floor slider spans −180…−40 dBFS; range slider 20…100 dB.
        floorSeek.bind(KEY_FLOOR, 50) { updateScale() }
        rangeSeek.bind(KEY_RANGE, 40) { updateScale() }
        peakText.setOnClickListener { if (!hasMicPermission()) requestMic() }
    }

    override fun onStart() {
        super.onStart()
        if (hasMicPermission()) engine.start() else requestMic()
    }

    override fun onStop() {
        engine.stop()
        prefs.edit().putFloat(KEY_SHIFT, engine.shiftHz).apply()
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

    private fun updateScale() {
        val floor = floorSeek.progress - 180
        val range = rangeSeek.progress + 20
        floorLabel.text = getString(R.string.floor_label, floor)
        rangeLabel.text = getString(R.string.range_label, range)
        waterfall.setScale(floor.toFloat(), range.toFloat())
        engine.detectFloorDb = floor.toFloat()
    }

    /** Wires a slider to [onChange], restoring and saving its position under [key] if given. */
    private fun SeekBar.bind(key: String? = null, default: Int = 0, onChange: (Int) -> Unit) {
        if (key != null) progress = prefs.getInt(key, default)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = onChange(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (key != null) prefs.edit().putInt(key, seekBar.progress).apply()
            }
        })
        onChange(progress)
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

    override fun onPeak(freqHz: Float?, levelDb: Float) = runOnUiThread {
        peakText.text = if (freqHz != null) getString(R.string.peak_detected, freqHz / 1000f, levelDb)
        else getString(R.string.peak_none)
    }

    override fun onError(message: String) = runOnUiThread {
        peakText.text = message
    }

    private companion object {
        const val REQ_MIC = 1
        const val KEY_SHIFT = "shift_hz"
        const val KEY_VOLUME = "volume_db"
        const val KEY_FLOOR = "floor"
        const val KEY_RANGE = "range"
    }
}
