package com.techazsure.deskless.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.techazsure.deskless.data.Stamp
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The phone's motion, light and proximity sensors, read for what a site walk needs: which way the camera
 * faces and how steeply, how bright the spot is, how far the inspector has walked, and whether the phone
 * is held to an ear. Screens call [watch] while they are showing and [unwatch] when they leave.
 */
class SiteSensors(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val rotationSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val lightSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val stepSensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    /** Degrees clockwise from magnetic north that the back camera faces; null when it points up or down. */
    var heading by mutableStateOf<Float?>(null)
        private set

    /** Degrees the back camera points above (+) or below (-) the horizon. */
    var pitch by mutableStateOf<Float?>(null)
        private set

    var lux by mutableStateOf<Float?>(null)
        private set

    /** Something is close to the top of the screen, as when the phone is held to an ear. */
    var near by mutableStateOf(false)
        private set

    /** The phone is held more upright than flat, as it is at an ear and is not when lying face down. */
    var upright by mutableStateOf(false)
        private set

    /** Steps counted since the app started counting; -1 until the phone has reported any. */
    var steps by mutableIntStateOf(-1)
        private set

    val hasCompass get() = rotationSensor != null || (accelerometer != null && magnetometer != null)
    val hasLight get() = lightSensor != null
    val hasProximity get() = proximitySensor != null
    val hasStepCounter get() = stepSensor != null

    /** Whether the user lets the app count steps. Phones before Android 10 do not ask. */
    val stepsAllowed: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private var watchers = 0
    private val matrix = FloatArray(9)
    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private var haveGravity = false
    private var haveMagnetic = false

    private var stepsAtStart = -1f
    private var stepsAtLastIssue = -1
    private var countingSteps = false
    private var earLock: PowerManager.WakeLock? = null

    fun watch() {
        if (watchers++ > 0) return
        listOfNotNull(rotationSensor, accelerometer, lightSensor, proximitySensor).forEach {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Without a fused rotation sensor the compass is worked out from gravity and the magnetic field.
        if (rotationSensor == null && magnetometer != null) {
            manager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun unwatch() {
        if (watchers == 0 || --watchers > 0) return
        listOfNotNull(rotationSensor, accelerometer, magnetometer, lightSensor, proximitySensor).forEach {
            manager.unregisterListener(this, it)
        }
        heading = null
        pitch = null
        lux = null
        near = false
        upright = false
        haveGravity = false
        haveMagnetic = false
        holdScreenOffAtEar(false)
    }

    /**
     * Starts the step count, once the user allows it. The phone only counts while something is listening,
     * so this stays on for as long as the app runs; the counter is a low-power part of the phone.
     */
    fun countSteps() {
        if (countingSteps || stepSensor == null || !stepsAllowed) return
        countingSteps = manager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** What the sensors read right now, for the picture just taken. Also restarts the step count. */
    fun stamp(zoom: Float, torch: Boolean): Stamp {
        val walked = if (steps >= 0 && stepsAtLastIssue >= 0) steps - stepsAtLastIssue else null
        if (steps >= 0) stepsAtLastIssue = steps
        return Stamp(heading = heading, pitch = pitch, lux = lux, steps = walked, zoom = zoom, torch = torch)
    }

    /**
     * While [on], the screen goes dark whenever the phone is at an ear, as it does in a call, so a cheek
     * cannot press anything. It comes back when the phone is lowered.
     */
    fun holdScreenOffAtEar(on: Boolean) {
        if (on) {
            if (earLock?.isHeld == true || !power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            earLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "deskless:ear").apply {
                setReferenceCounted(false)
                runCatching { acquire(10 * 60 * 1000L) }
            }
        } else {
            earLock?.takeIf { it.isHeld }?.let { runCatching { it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } }
            earLock = null
        }
    }

    /** Every sensor the phone reports, by name, for the instrument panel. */
    fun inventory(): List<String> = manager.getSensorList(Sensor.TYPE_ALL).map { it.name }.distinct().sorted()

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                orient()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // A slow average of the accelerometer is gravity: the direction of "up" in the phone's own axes.
                val keep = if (haveGravity) 0.85f else 0f
                for (i in 0..2) gravity[i] = keep * gravity[i] + (1 - keep) * event.values[i]
                haveGravity = true
                val size = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
                if (size > 1f) upright = abs(gravity[2]) / size < 0.75f
                if (rotationSensor == null && haveMagnetic && SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) orient()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magnetic, 0, 0, 3)
                haveMagnetic = true
            }
            Sensor.TYPE_LIGHT -> lux = event.values[0].roundToInt().toFloat()
            Sensor.TYPE_PROXIMITY -> near = event.values[0] < minOf(event.sensor.maximumRange, 5f)
            Sensor.TYPE_STEP_COUNTER -> {
                // The phone reports steps since it was switched on; the walk starts from the first report.
                if (stepsAtStart < 0f) stepsAtStart = event.values[0]
                steps = (event.values[0] - stepsAtStart).toInt()
                if (stepsAtLastIssue < 0) stepsAtLastIssue = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Reads the camera's direction off the rotation matrix. The matrix turns the phone's axes into the
     * world's (east, north, up), and the back camera looks along the phone's minus-z axis.
     */
    private fun orient() {
        val east = -matrix[2]
        val north = -matrix[5]
        val up = (-matrix[8]).coerceIn(-1f, 1f)
        val elevation = Math.toDegrees(asin(up.toDouble())).toFloat()
        pitch = elevation.roundToInt().toFloat()
        // Pointing nearly straight up or down, the camera has no compass direction worth the name.
        heading = if (abs(elevation) > 65f) {
            null
        } else {
            (((Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360.0).roundToInt()) % 360).toFloat()
        }
    }
}
