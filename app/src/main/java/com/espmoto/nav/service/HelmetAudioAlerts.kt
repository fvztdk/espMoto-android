package com.espmoto.nav.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.espmoto.nav.ble.ManeuverType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesizes navigation and link-status audio earcons for Bluetooth helmet speakers,
 * requesting transient ducking audio focus so music/podcasts dip briefly and resume.
 */
object HelmetAudioAlerts {
    private const val PREFS_NAME = "espmoto_audio_prefs"
    private const val KEY_SOUND_WARNINGS = "sound_warnings_enabled"

    private const val SAMPLE_RATE_HZ = 44_100
    private const val INTER_NOTE_GAP_MS = 35
    private const val ENVELOPE_MS = 8
    private const val AMPLITUDE_SCALE = 0.55
    private const val BLE_DISCONNECT_COOLDOWN_MS = 10_000L

    private data class ToneNote(
        val frequencyHz: Double,
        val durationMs: Int
    )

    private val STAGE_1_CHIME = listOf(
        ToneNote(880.0, 110),
        ToneNote(1175.0, 150)
    )
    private val STAGE_2_TRIAD = listOf(
        ToneNote(1047.0, 90),
        ToneNote(1319.0, 90),
        ToneNote(1568.0, 170)
    )
    private val OFF_ROUTE_ALERT = listOf(
        ToneNote(988.0, 130),
        ToneNote(659.0, 200)
    )
    private val BLE_CONNECTED_CHIRP = listOf(
        ToneNote(659.0, 80),
        ToneNote(988.0, 130)
    )
    private val BLE_DISCONNECTED_ALERT = listOf(
        ToneNote(880.0, 100),
        ToneNote(659.0, 100),
        ToneNote(440.0, 190)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val playMutex = Mutex()
    private val lock = Any()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    private var trackedRouteId: Int = 0
    private var earlyAlertedStep: Int = -1
    private var finalAlertedStep: Int = -1
    private var lastDisconnectAlertMs: Long = 0L

    fun load(context: Context): Boolean {
        val ctx = context.applicationContext
        appContext = ctx
        synchronized(lock) {
            if (loaded) return _soundEnabled.value
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean(KEY_SOUND_WARNINGS, true)
            _soundEnabled.value = enabled
            loaded = true
            return enabled
        }
    }

    fun setEnabled(context: Context, enabled: Boolean, playPreview: Boolean = true) {
        val ctx = context.applicationContext
        appContext = ctx
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SOUND_WARNINGS, enabled).apply()
            _soundEnabled.value = enabled
            loaded = true
        }
        if (enabled && playPreview) {
            playBleConnected()
        }
    }

    fun playTestSound(context: Context) {
        load(context)
        enqueueSequence(STAGE_1_CHIME + STAGE_2_TRIAD)
    }

    fun resetStepTracking() {
        synchronized(lock) {
            trackedRouteId = 0
            earlyAlertedStep = -1
            finalAlertedStep = -1
        }
    }

    fun evaluateTurnDistance(
        routeId: Int,
        stepIndex: Int,
        maneuver: ManeuverType,
        distMeters: Double,
        segmentTotalMeters: Double,
        speedMps: Double
    ) {
        if (!_soundEnabled.value) return
        if (!distMeters.isFinite() || distMeters < 0.0) return
        if (maneuver == ManeuverType.NONE || maneuver == ManeuverType.STRAIGHT) return

        val (earlyMeters, finalMeters) = when {
            speedMps > 23.6 -> 700.0 to 160.0   // Highway (> 85 km/h)
            speedMps >= 13.9 -> 450.0 to 110.0  // Secondary road (50-85 km/h)
            else -> 250.0 to 70.0               // City (< 50 km/h)
        }

        var notesToPlay: List<ToneNote>? = null
        synchronized(lock) {
            if (routeId != trackedRouteId) {
                trackedRouteId = routeId
                earlyAlertedStep = -1
                finalAlertedStep = -1
            }

            if (distMeters <= finalMeters && finalAlertedStep != stepIndex) {
                finalAlertedStep = stepIndex
                earlyAlertedStep = stepIndex
                notesToPlay = STAGE_2_TRIAD
            } else if (
                distMeters <= earlyMeters &&
                distMeters > finalMeters &&
                earlyAlertedStep != stepIndex &&
                segmentTotalMeters >= earlyMeters + 40.0
            ) {
                earlyAlertedStep = stepIndex
                notesToPlay = STAGE_1_CHIME
            }
        }

        notesToPlay?.let { enqueueSequence(it) }
    }

    fun playOffRouteRerouting() {
        if (!_soundEnabled.value) return
        enqueueSequence(OFF_ROUTE_ALERT)
    }

    fun playBleConnected() {
        if (!_soundEnabled.value) return
        enqueueSequence(BLE_CONNECTED_CHIRP)
    }

    fun playBleDisconnectedUnexpectedly() {
        if (!_soundEnabled.value) return
        val shouldPlay = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (lastDisconnectAlertMs == 0L || now - lastDisconnectAlertMs >= BLE_DISCONNECT_COOLDOWN_MS) {
                lastDisconnectAlertMs = now
                true
            } else {
                false
            }
        }
        if (shouldPlay) {
            enqueueSequence(BLE_DISCONNECTED_ALERT)
        }
    }

    private fun enqueueSequence(notes: List<ToneNote>) {
        val ctx = appContext ?: return
        if (notes.isEmpty()) return

        scope.launch {
            playMutex.withLock {
                playSequenceBlocking(ctx, notes)
            }
        }
    }

    private suspend fun playSequenceBlocking(context: Context, notes: List<ToneNote>) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val samples = synthesizeNotes(notes)
        if (samples.isEmpty()) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()

        var focusGranted = false
        var track: AudioTrack? = null
        try {
            val focusRes = runCatching { audioManager.requestAudioFocus(focusReq) }.getOrNull()
            focusGranted = focusRes == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()

            val written = track.write(samples, 0, samples.size)
            if (written > 0 && track.state == AudioTrack.STATE_INITIALIZED) {
                val totalDurationMs = (samples.size * 1000L) / SAMPLE_RATE_HZ
                track.play()
                delay(totalDurationMs + 60L)
            }
        } catch (_: Exception) {
        } finally {
            track?.let { t ->
                runCatching {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        t.stop()
                    }
                }
                runCatching { t.release() }
            }
            if (focusGranted) {
                runCatching { audioManager.abandonAudioFocusRequest(focusReq) }
            }
        }
    }

    private fun synthesizeNotes(notes: List<ToneNote>): ShortArray {
        val gapSamples = (SAMPLE_RATE_HZ * INTER_NOTE_GAP_MS) / 1000
        val totalSamples = notes.sumOf { (SAMPLE_RATE_HZ * it.durationMs) / 1000 } +
            (notes.size - 1).coerceAtLeast(0) * gapSamples
        if (totalSamples <= 0) return ShortArray(0)

        val pcm = ShortArray(totalSamples)
        var offset = 0
        val envSamples = (SAMPLE_RATE_HZ * ENVELOPE_MS) / 1000

        notes.forEachIndexed { idx, note ->
            val noteSamples = (SAMPLE_RATE_HZ * note.durationMs) / 1000
            val rampSamples = envSamples.coerceAtMost(noteSamples / 2)
            for (i in 0 until noteSamples) {
                val phase = 2.0 * PI * note.frequencyHz * i / SAMPLE_RATE_HZ
                val wave = 0.82 * sin(phase) + 0.18 * sin(2.0 * phase)
                val envelope = when {
                    rampSamples > 0 && i < rampSamples ->
                        0.5 * (1.0 - cos(PI * i / rampSamples))
                    rampSamples > 0 && i >= noteSamples - rampSamples ->
                        0.5 * (1.0 - cos(PI * (noteSamples - 1 - i) / rampSamples))
                    else -> 1.0
                }
                val sample = (wave * envelope * AMPLITUDE_SCALE * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcm[offset + i] = sample.toShort()
            }
            offset += noteSamples
            if (idx < notes.lastIndex) {
                offset += gapSamples
            }
        }
        return pcm
    }
}
