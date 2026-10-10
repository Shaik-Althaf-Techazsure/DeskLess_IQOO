package com.techazsure.deskless.capture

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * What the phone's motor says, so the inspector can keep looking at the site and not at the screen: each
 * event has its own feel. Silent on a phone without a motor.
 */
class Haptics(context: Context) {
    private val motor: Vibrator? = found(context)?.takeIf { it.hasVibrator() }

    private fun found(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** One crisp click: a picture was taken, a reading was saved, a tag was read. */
    fun click() = play(longArrayOf(0, 25), intArrayOf(0, 200))

    /** Two light taps: the AI has filled the report fields. */
    fun filled() = play(longArrayOf(0, 20, 90, 20), intArrayOf(0, 140, 0, 140))

    /** Three firm pulses: the AI rated the issue High or Critical. */
    fun alert() = play(longArrayOf(0, 70, 80, 70, 80, 140), intArrayOf(0, 255, 0, 255, 0, 255))

    /** One long buzz at the ear: recording has started. */
    fun listening() = play(longArrayOf(0, 120), intArrayOf(0, 220))

    /** Two short buzzes at the ear: recording has stopped. */
    fun heard() = play(longArrayOf(0, 45, 70, 45), intArrayOf(0, 220, 0, 220))

    /** The faint tick of a level finding true. */
    fun tick() = play(longArrayOf(0, 12), intArrayOf(0, 110))

    private fun play(timings: LongArray, strengths: IntArray) {
        val vibrator = motor ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // A motor that cannot vary its strength plays the same rhythm at full strength.
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, strengths, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
    }
}
