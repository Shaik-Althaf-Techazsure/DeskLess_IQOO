package com.techazsure.deskless.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** A position, and the radius in metres it is good to. */
data class GeoFix(val lat: Double, val lon: Double, val accuracy: Float)

/**
 * Finds where the phone is with Android's own location service, which needs no network: satellites alone
 * are enough, so an issue is stamped even in airplane mode. It never blocks and never throws.
 */
class Locator(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    val allowed: Boolean
        get() = PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Calls [onResult] once, on the main thread: with a position from the last minute if there is one,
     * otherwise a fresh one, otherwise one from the last ten minutes, otherwise null.
     */
    @SuppressLint("MissingPermission")
    fun locate(onResult: (GeoFix?) -> Unit) {
        if (!allowed) {
            onResult(null)
            return
        }
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val recent = known.filter { ageSeconds(it) < 60 }.minByOrNull { it.accuracy }
        // An older position is better than none, but not one from before the walk began.
        val fallback = known.filter { ageSeconds(it) < STALE_SECONDS }.maxByOrNull { it.elapsedRealtimeNanos }?.let(::fixOf)
        if (recent != null) {
            onResult(fixOf(recent))
            return
        }
        // The fused source is quickest indoors; plain satellites are the one that always exists.
        val provider = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { it in providers }
        if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(fallback)
            return
        }

        var answered = false
        val cancel = CancellationSignal()
        fun answer(fix: GeoFix?) {
            if (answered) return
            answered = true
            cancel.cancel()
            onResult(fix)
        }
        handler.postDelayed({ answer(fallback) }, WAIT_MILLIS)
        runCatching {
            manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                answer(location?.let(::fixOf) ?: fallback)
            }
        }.onFailure { answer(fallback) }
    }

    private val keepWarm = object : LocationListener {
        override fun onLocationChanged(location: Location) = Unit

        // Empty on purpose: older Android versions call these and have no default for them.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * Keeps the phone finding its position while the camera is open, so that a picture is stamped at once
     * with a fresh position and does not have to wait for the first fix. Satellites take longest indoors.
     */
    @SuppressLint("MissingPermission")
    fun warm() {
        if (!allowed) return
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.GPS_PROVIDER,
        ).filter { it in providers }.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 2_000L, 0f, keepWarm, Looper.getMainLooper()) }
        }
    }

    fun cool() {
        runCatching { manager.removeUpdates(keepWarm) }
    }

    private fun fixOf(location: Location) = GeoFix(location.latitude, location.longitude, location.accuracy)

    private fun ageSeconds(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        /** How long an issue waits for a fresh position before settling for the last known one. */
        private const val WAIT_MILLIS = 12_000L

        /** A last-known position older than this is not trusted to be where the issue is. */
        private const val STALE_SECONDS = 600L
    }
}
