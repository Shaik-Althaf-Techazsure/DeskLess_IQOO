package com.techazsure.deskless.capture

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * The small amount of map arithmetic the issue map needs. There are no map tiles in DeskLess, so the map
 * is the issues themselves, laid out by how many metres apart they were captured.
 */
object Geo {
    /** Metres east and north of the middle of the group. */
    data class Point(val east: Float, val north: Float)

    /** "12.97160° N, 77.59460° E" */
    fun label(lat: Double, lon: Double): String = String.format(
        Locale.ENGLISH, "%.5f° %s, %.5f° %s",
        abs(lat), if (lat >= 0) "N" else "S",
        abs(lon), if (lon >= 0) "E" else "W",
    )

    /** Lays positions out flat around their centre. Good to well under a metre over any one site. */
    fun project(positions: List<Pair<Double, Double>>): List<Point> {
        if (positions.isEmpty()) return emptyList()
        val lat0 = positions.map { it.first }.average()
        val lon0 = positions.map { it.second }.average()
        val metresPerDegreeEast = 111_320.0 * cos(Math.toRadians(lat0))
        return positions.map { (lat, lon) ->
            Point(((lon - lon0) * metresPerDegreeEast).toFloat(), ((lat - lat0) * 110_540.0).toFloat())
        }
    }

    /** The side, in metres, of the square the points fit in; never less than [floor] so one spot is not blown up. */
    fun span(points: List<Point>, floor: Float = 12f): Float {
        if (points.isEmpty()) return floor
        val width = points.maxOf { it.east } - points.minOf { it.east }
        val height = points.maxOf { it.north } - points.minOf { it.north }
        return max(floor, max(width, height))
    }

    /** A round length for the scale bar, about a quarter of the span: 1, 2, 5, 10, 20, 50 … metres. */
    fun scaleBar(span: Float): Int {
        val steps = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000)
        return steps.lastOrNull { it <= span / 3f } ?: 1
    }

    fun distance(metres: Int): String = if (metres >= 1000) "${metres / 1000} km" else "$metres m"
}
