package com.techazsure.deskless.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A spirit level made of the accelerometer. Lying flat on a surface it reads the surface's slope; standing
 * on an edge it reads how far that edge is off level, or the side edge off plumb. [setZero] takes out the
 * phone's own error, such as a camera bump that keeps its back off the table.
 */
class LevelMeter(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val saved = context.getSharedPreferences("level", Context.MODE_PRIVATE)

    val available get() = sensor != null

    /** Degrees off level, after the zero is taken out. */
    var degrees by mutableFloatStateOf(0f)
        private set

    /** True when the phone stands on an edge, false when it lies flat. */
    var onEdge by mutableStateOf(false)
        private set

    /** Where the bubble sits, each -1..1 from the middle. On an edge only [bubbleX] moves. */
    var bubbleX by mutableFloatStateOf(0f)
        private set
    var bubbleY by mutableFloatStateOf(0f)
        private set

    var ready by mutableStateOf(false)
        private set

    private val g = FloatArray(3)
    private var flatZero = floatArrayOf(saved.getFloat("fx", 0f), saved.getFloat("fy", 0f))
    private var edgeZero = saved.getFloat("edge", 0f)

    fun start() {
        ready = false
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = manager.unregisterListener(this)

    /** Calls the way the phone rests now "level". Do it on a surface known to be true. */
    fun setZero() {
        val size = size()
        if (size < 1f) return
        if (onEdge) {
            edgeZero = edgeAngle(size)
        } else {
            flatZero = floatArrayOf(g[0] / size, g[1] / size)
        }
        saved.edit().putFloat("fx", flatZero[0]).putFloat("fy", flatZero[1]).putFloat("edge", edgeZero).apply()
    }

    fun clearZero() {
        flatZero = floatArrayOf(0f, 0f)
        edgeZero = 0f
        saved.edit().clear().apply()
    }

    val zeroed get() = flatZero[0] != 0f || flatZero[1] != 0f || edgeZero != 0f

    override fun onSensorChanged(event: SensorEvent) {
        val keep = if (ready) 0.9f else 0f
        for (i in 0..2) g[i] = keep * g[i] + (1 - keep) * event.values[i]
        val size = size()
        if (size < 1f) return
        ready = true
        onEdge = abs(g[2]) / size < 0.8f
        if (onEdge) {
            val angle = edgeAngle(size) - edgeZero
            degrees = abs(angle)
            bubbleX = (angle / RANGE).coerceIn(-1f, 1f)
            bubbleY = 0f
        } else {
            // The tilt is how far "up" has moved, in the phone's own axes, from where it is on a level surface.
            val x = g[0] / size - flatZero[0]
            val y = g[1] / size - flatZero[1]
            val across = sqrt(x * x + y * y).coerceAtMost(1f)
            degrees = Math.toDegrees(asin(across.toDouble())).toFloat()
            val full = sin(Math.toRadians(RANGE.toDouble())).toFloat()
            bubbleX = (x / full).coerceIn(-1f, 1f)
            bubbleY = (-y / full).coerceIn(-1f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun size() = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])

    /** The slope, in degrees, of the edge the phone stands on: the share of gravity that runs along it. */
    private fun edgeAngle(size: Float): Float {
        val along = if (abs(g[0]) < abs(g[1])) g[0] else g[1]
        return Math.toDegrees(asin((along / size).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    companion object {
        /** The tilt, in degrees, that sends the bubble to the end of its travel. */
        const val RANGE = 10f
    }
}

/** A sound level reading from the microphone, averaged over a few seconds. Indicative; see [Signal.decibels]. */
class NoiseMeter {
    @Volatile
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** The level right now, in decibels, while a measurement runs. */
    var now by mutableFloatStateOf(0f)
        private set

    /** How far through the measurement, 0..1. */
    var progress by mutableFloatStateOf(0f)
        private set

    var measuring by mutableStateOf(false)
        private set

    /**
     * Listens for [seconds] and calls [onDone] on the main thread with the average and the loudest moment,
     * or with nulls if the microphone could not be opened. The caller must hold RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    fun measure(seconds: Int = 3, onDone: (average: Float?, peak: Float?) -> Unit) {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = if (minBuffer <= 0) null else AudioRecord(
            // The source Android asks makers to leave unprocessed and to tune to a known level.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, RATE),
        )
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            onDone(null, null)
            return
        }
        running = true
        measuring = true
        progress = 0f
        thread(name = "noise-meter") {
            val window = ShortArray(RATE / 10)
            val total = seconds * 10
            var squares = 0.0
            var count = 0L
            var peak = 0f
            var done = 0
            try {
                record.startRecording()
                // The first moments carry the click of the microphone opening; leave them out.
                repeat(3) { record.read(window, 0, window.size) }
                while (running && done < total) {
                    val read = record.read(window, 0, window.size)
                    if (read <= 0) break
                    var sum = 0.0
                    for (i in 0 until read) sum += window[i].toDouble() * window[i]
                    squares += sum
                    count += read
                    val level = Signal.decibels(sqrt(sum / read))
                    peak = max(peak, level)
                    done++
                    val share = done / total.toFloat()
                    main.post {
                        now = level
                        progress = share
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                val cancelled = !running
                val completed = count > 0 && done == total
                val average = if (count > 0) Signal.decibels(sqrt(squares / count)) else 0f
                running = false
                main.post {
                    measuring = false
                    // A measurement the user walked away from reports nothing at all.
                    if (!cancelled) if (completed) onDone(average, peak) else onDone(null, null)
                }
            }
        }
    }

    fun cancel() {
        running = false
    }

    private companion object {
        const val RATE = 44_100
    }
}

/**
 * A vibration reading: the phone rests on the thing being checked for a few seconds while the accelerometer
 * runs as fast as it can. Gives how strongly it shakes and the frequency that stands out.
 */
class VibrationMeter(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())

    val available get() = sensor != null

    /** How hard it is shaking right now, in m/s², for a live trace. */
    var now by mutableFloatStateOf(0f)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var measuring by mutableStateOf(false)
        private set

    private val x = ArrayList<Float>()
    private val y = ArrayList<Float>()
    private val z = ArrayList<Float>()
    private var startedAt = 0L
    private var lastAt = 0L
    private var lengthNanos = 0L
    private val mean = FloatArray(3)
    private var onDone: ((Signal.Vibration?) -> Unit)? = null

    fun measure(seconds: Int = 5, onDone: (Signal.Vibration?) -> Unit) {
        if (measuring) return
        val source = sensor
        if (source == null) {
            onDone(null)
            return
        }
        x.clear()
        y.clear()
        z.clear()
        startedAt = 0L
        lengthNanos = seconds * 1_000_000_000L
        this.onDone = onDone
        progress = 0f
        measuring = true
        manager.registerListener(this, source, SensorManager.SENSOR_DELAY_FASTEST)
        // If the sensor delivers nothing, do not wait for ever.
        main.postDelayed({ if (measuring && x.isEmpty()) finish() }, seconds * 1000L + 1500L)
    }

    fun cancel() {
        if (!measuring) return
        manager.unregisterListener(this)
        measuring = false
        onDone = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!measuring) return
        if (startedAt == 0L) {
            startedAt = event.timestamp
            event.values.copyInto(mean, 0, 0, 3)
        }
        lastAt = event.timestamp
        x.add(event.values[0])
        y.add(event.values[1])
        z.add(event.values[2])
        // The live trace: how far this sample is from a slow average, which stands in for gravity.
        var away = 0f
        for (i in 0..2) {
            mean[i] = 0.98f * mean[i] + 0.02f * event.values[i]
            away += (event.values[i] - mean[i]) * (event.values[i] - mean[i])
        }
        now = 0.8f * now + 0.2f * sqrt(away)
        progress = ((event.timestamp - startedAt).toFloat() / lengthNanos).coerceIn(0f, 1f)
        if (event.timestamp - startedAt >= lengthNanos) finish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finish() {
        manager.unregisterListener(this)
        measuring = false
        val seconds = (lastAt - startedAt) / 1e9f
        val result = if (x.size < 32 || seconds <= 0f) {
            null
        } else {
            Signal.vibration(x.toFloatArray(), y.toFloatArray(), z.toFloatArray(), rate = (x.size - 1) / seconds)
        }
        val report = onDone
        onDone = null
        report?.invoke(result)
    }
}
