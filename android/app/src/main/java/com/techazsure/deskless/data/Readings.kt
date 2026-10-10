package com.techazsure.deskless.data

import com.techazsure.deskless.capture.Geo
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * What the phone's own sensors read at the moment a picture was taken. Each value is null when the phone
 * could not give one. These are indicative readings from a phone, not calibrated measurements.
 */
data class Stamp(
    /** Degrees clockwise from magnetic north that the camera faced; null when it pointed nearly straight up or down. */
    val heading: Float? = null,
    /** How far the camera pointed above (+) or below (-) the horizon, in degrees. */
    val pitch: Float? = null,
    /** Brightness at the phone, in lux. */
    val lux: Float? = null,
    /** Steps walked since the previous issue was captured. */
    val steps: Int? = null,
    /** The zoom the picture was taken at; 1 is the main camera. */
    val zoom: Float = 1f,
    /** Whether the phone's torch lit the picture. */
    val torch: Boolean = false,
)

enum class Instrument(val label: String) {
    LEVEL("Level"),
    NOISE("Noise"),
    VIBRATION("Vibration"),
}

/** One reading the inspector took with the phone as an instrument. */
data class Reading(
    val kind: Instrument,
    /** LEVEL: degrees off level or plumb. NOISE: average decibels. VIBRATION: RMS acceleration in m/s². */
    val value: Float,
    /**
     * LEVEL: 1 when the phone stood on its edge, 0 when it lay flat. NOISE: the loudest moment, in decibels.
     * VIBRATION: the main frequency in hertz, or 0 when none stood out.
     */
    val extra: Float = 0f,
    val takenAt: Long = 0,
)

/** The wording for sensor readings, shared by the screens, the report and what the on-device model is told. */
object Readings {
    private val POINTS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
    private val SHORT = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    private fun pointIndex(heading: Float): Int = ((((heading % 360f) + 360f) % 360f + 22.5f) / 45f).toInt() % 8

    /** "north-east" for 47°. */
    fun compassPoint(heading: Float): String = POINTS[pointIndex(heading)]

    /** "NE" for 47°. */
    fun compassShort(heading: Float): String = SHORT[pointIndex(heading)]

    /** Where the camera was aimed when it was clearly up or down; null for an ordinary shot. */
    fun aim(pitch: Float?): String? = when {
        pitch == null -> null
        pitch >= 55f -> "overhead"
        pitch <= -55f -> "at ground level"
        else -> null
    }

    fun light(lux: Float): String = when {
        lux < 10f -> "dark"
        lux < 50f -> "very dim"
        lux < 150f -> "dim"
        lux < 1_000f -> "normal indoor light"
        lux < 10_000f -> "bright"
        else -> "daylight"
    }

    fun noise(decibels: Float): String = when {
        decibels < 50f -> "quiet"
        decibels < 70f -> "moderate"
        decibels < 85f -> "loud"
        else -> "very loud"
    }

    fun vibration(rms: Float): String = when {
        rms < 0.05f -> "still"
        rms < 0.3f -> "slight"
        rms < 1.5f -> "noticeable"
        else -> "strong"
    }

    /** True for a reading that is itself worth a second look: clearly out of level, very loud, or shaking hard. */
    fun notable(reading: Reading): Boolean = when (reading.kind) {
        Instrument.LEVEL -> reading.value >= 1f
        Instrument.NOISE -> reading.value >= 85f
        Instrument.VIBRATION -> reading.value >= 1.5f
    }

    /** A slope in the builder's unit: millimetres of rise per metre of run. */
    fun millimetresPerMetre(degrees: Float): Int = (tan(Math.toRadians(degrees.toDouble())) * 1000).roundToInt()

    /** One reading in words: "Surface slope 1.8° (31 mm per metre)". */
    fun describe(reading: Reading): String = when (reading.kind) {
        Instrument.LEVEL ->
            (if (reading.extra >= 0.5f) "Edge tilt " else "Surface slope ") +
                String.format(Locale.ENGLISH, "%.1f° (%d mm per metre)", reading.value, millimetresPerMetre(reading.value))
        Instrument.NOISE ->
            String.format(Locale.ENGLISH, "Noise %.0f dB (%s)", reading.value, noise(reading.value)) +
                if (reading.extra > reading.value + 0.5f) String.format(Locale.ENGLISH, ", peaks %.0f dB", reading.extra) else ""
        Instrument.VIBRATION ->
            String.format(Locale.ENGLISH, "Vibration %.2f m/s² (%s)", reading.value, vibration(reading.value)) +
                if (reading.extra > 0f) String.format(Locale.ENGLISH, ", mainly at %.0f Hz", reading.extra) else ""
    }

    /** What the capture stamp says, as short phrases in reading order. */
    fun describe(stamp: Stamp): List<String> = listOfNotNull(
        stamp.lux?.let { String.format(Locale.ENGLISH, "Light %.0f lux (%s)", it, light(it)) },
        stamp.heading?.let { "facing ${compassPoint(it)}" },
        aim(stamp.pitch)?.let { "camera aimed $it" },
        stamp.steps?.takeIf { it > 0 }?.let { if (it == 1) "1 step from the previous issue" else "$it steps from the previous issue" },
        stamp.zoom.takeIf { it < 0.95f || it > 1.05f }?.let { String.format(Locale.ENGLISH, "%.1f× zoom", it).replace(".0×", "×") },
        if (stamp.torch) "torch on" else null,
    )

    /**
     * The readings worth telling the on-device model, one per line, or "" when there are none. Compass and
     * step count are left out: they place the issue but say nothing about what is wrong.
     */
    fun forModel(issue: Issue): String {
        val stamp = issue.stamp
        val lines = listOfNotNull(
            issue.place.takeIf { it.isNotBlank() }?.let { "The phone was tapped on the tag for: $it" },
            stamp?.lux?.let { String.format(Locale.ENGLISH, "Light at the spot: %.0f lux (%s)", it, light(it)) + if (stamp.torch) ", so the torch was used" else "" },
            aim(stamp?.pitch)?.let { "The camera was aimed $it" },
        ) + issue.readings.map(::describe)
        return lines.joinToString("\n") { "- $it" }
    }

    /**
     * The location for the report, put together from the tagged place, where the note says it is, and where
     * the camera was aimed. Nothing is repeated: a part already named in the spoken location is left out.
     */
    fun location(place: String, spoken: String, aim: String?): String {
        val said = spoken.trim()
        fun missing(part: String) = part.isNotBlank() && !said.contains(part.trim(), ignoreCase = true)
        return listOfNotNull(
            place.trim().takeIf { missing(it) },
            said.takeIf { it.isNotEmpty() },
            aim?.takeIf { missing(it) && !said.contains(aimWord(it), ignoreCase = true) },
        ).joinToString(", ")
    }

    /**
     * What goes in Location when nobody has said where the issue is: what the phone itself knows. The
     * tagged place, where the camera was aimed and which way it faced, and the satellite position. Blank
     * only when the phone knows none of them.
     */
    fun whereKnown(place: String, stamp: Stamp?, lat: Double?, lon: Double?): String = listOfNotNull(
        place.trim().takeIf { it.isNotEmpty() },
        aim(stamp?.pitch),
        stamp?.heading?.let { "facing ${compassPoint(it)}" },
        if (lat != null && lon != null) "at ${Geo.label(lat, lon)}" else null,
    ).joinToString(", ").replaceFirstChar { it.uppercase(Locale.ENGLISH) }

    /** The word a person would use for the same thing: "ceiling" for "overhead", "floor" for "at ground level". */
    private fun aimWord(aim: String): String = if (aim == "overhead") "ceiling" else "floor"
}
