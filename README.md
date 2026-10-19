# Ultrasound

A minimal, single-screen Android app (Kotlin, no AndroidX/Compose) to see and hear ultrasound with the phone's microphone.

- **Waterfall** of the upper spectrum (15 kHz → Nyquist), newest at the top. Dashed line = 20 kHz (limit of human hearing).
- **Detection** readout of the strongest ultrasonic tone (>20 kHz, ≥15 dB above the noise median).
- **Listen**: plays a pitch-shifted copy to the current media output (e.g. Bluetooth headphones). Every frequency *f* is heard at *f − shift*; the blue line on the waterfall marks what you hear as 0 Hz. Shift and volume are live-adjustable.
- System light/dark theme (`Theme.DeviceDefault.DayNight`), edge-to-edge.

## How it works
- Records with `UNPROCESSED` (or `VOICE_RECOGNITION`) from the built-in mic, trying 192 → 96 → 48 → 44.1 kHz. The built-in mic stays selected even with a Bluetooth headset connected.
- Pitch shift is single-sideband: a Hilbert FIR builds the analytic signal, which is multiplied by a complex oscillator. Unlike a plain heterodyne mixer there's no sum-frequency image. A high-pass at the shift frequency stops lower content folding back; the result is low-passed, decimated to 48 kHz, and soft-limited.

## Caveats
- Ultrasound visibility depends on the phone. Many devices accept 96/192 kHz but internally resample from 48 kHz, so above 24 kHz shows nothing. MEMS mics typically respond well up to ~40–60 kHz on devices with genuine high-rate capture.
- Bluetooth adds roughly 150–300 ms of latency.

## Build
```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```
Requires the Android SDK (compileSdk 35); minSdk 30 (Android 11).
