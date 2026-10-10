package com.techazsure.deskless

import com.techazsure.deskless.capture.Signal
import com.techazsure.deskless.data.Instrument
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Reading
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ReadingsTest {
    @Test
    fun compassPoints() {
        assertEquals("north", Readings.compassPoint(350f))
        assertEquals("north", Readings.compassPoint(10f))
        assertEquals("north-east", Readings.compassPoint(47f))
        assertEquals("SW", Readings.compassShort(225f))
        assertEquals("west", Readings.compassPoint(-90f))
    }

    @Test
    fun cameraAim() {
        assertEquals("overhead", Readings.aim(70f))
        assertEquals("at ground level", Readings.aim(-80f))
        assertNull(Readings.aim(10f))
        assertNull(Readings.aim(null))
    }

    @Test
    fun readingsInWords() {
        assertEquals("Surface slope 1.8° (31 mm per metre)", Readings.describe(Reading(Instrument.LEVEL, 1.8f)))
        assertEquals("Edge tilt 0.5° (9 mm per metre)", Readings.describe(Reading(Instrument.LEVEL, 0.5f, extra = 1f)))
        assertEquals("Noise 86 dB (very loud), peaks 92 dB", Readings.describe(Reading(Instrument.NOISE, 86f, extra = 92f)))
        assertEquals("Vibration 0.82 m/s² (noticeable), mainly at 50 Hz", Readings.describe(Reading(Instrument.VIBRATION, 0.82f, extra = 50f)))
        assertEquals("Vibration 0.02 m/s² (still)", Readings.describe(Reading(Instrument.VIBRATION, 0.02f)))
    }

    @Test
    fun stampInWords() {
        val words = Readings.describe(Stamp(heading = 47f, pitch = 70f, lux = 38f, steps = 24, zoom = 3f, torch = true))
        assertEquals(
            listOf("Light 38 lux (very dim)", "facing north-east", "camera aimed overhead", "24 steps from the previous issue", "3× zoom", "torch on"),
            words,
        )
        assertTrue(Readings.describe(Stamp()).isEmpty())
    }

    @Test
    fun whatTheModelIsTold() {
        val issue = Issue(
            id = "a", createdAt = 0, photoFile = "p.jpg", place = "Pump room",
            stamp = Stamp(heading = 10f, lux = 6f, torch = true),
            readings = listOf(Reading(Instrument.NOISE, 88f, extra = 88f)),
        )
        assertEquals(
            "- The phone was tapped on the tag for: Pump room\n" +
                "- Light at the spot: 6 lux (dark), so the torch was used\n" +
                "- Noise 88 dB (very loud)",
            Readings.forModel(issue),
        )
        assertEquals("", Readings.forModel(Issue(id = "b", createdAt = 0, photoFile = "p.jpg")))
    }

    @Test
    fun locationIsPutTogetherWithoutRepeats() {
        assertEquals("Pump room, near the north wall, overhead", Readings.location("Pump room", "near the north wall", "overhead"))
        assertEquals("Pump room ceiling", Readings.location("Pump room", "Pump room ceiling", "overhead"))
        assertEquals("Pump room", Readings.location("Pump room", "", null))
        assertEquals("Second floor", Readings.location("", "Second floor", null))
        assertEquals("at ground level", Readings.location("", "", "at ground level"))
        assertEquals("", Readings.location("", "", null))
    }

    @Test
    fun vibrationFindsTheFrequency() {
        // A 50 Hz shake of amplitude 1 m/s² on one axis, sampled 400 times a second on top of gravity.
        val rate = 400f
        val n = 2000
        val x = FloatArray(n) { sin(2 * PI * 50.0 * it / rate).toFloat() }
        val y = FloatArray(n)
        val z = FloatArray(n) { 9.81f }
        val found = Signal.vibration(x, y, z, rate)
        assertEquals(1f / sqrt(2f), found.rms, 0.02f)
        assertEquals(50f, found.hertz, 0.5f)
    }

    @Test
    fun aStillPhoneHasNoFrequency() {
        val z = FloatArray(1024) { 9.81f }
        val found = Signal.vibration(FloatArray(1024), FloatArray(1024), z, 200f)
        assertEquals(0f, found.rms, 0.001f)
        assertEquals(0f, found.hertz, 0f)
    }

    @Test
    fun soundLevelFollowsAndroidsRule() {
        assertEquals(90f, Signal.decibels(2500.0), 0.01f)
        assertEquals(70f, Signal.decibels(250.0), 0.01f)
    }
}
