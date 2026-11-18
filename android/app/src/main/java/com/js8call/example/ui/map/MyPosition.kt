package com.js8call.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.js8call.example.reports.Geo
import com.js8call.example.reports.LatLon

/** This station's position: last GPS fix if location is allowed, else the grid square from Settings. */
@SuppressLint("MissingPermission")
fun myPosition(ctx: Context): LatLon? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }
            .getOrNull()?.let { return LatLon(it.latitude, it.longitude) }
    }
    val grid = PreferenceManager.getDefaultSharedPreferences(ctx).getString("grid", "").orEmpty()
    return Geo.maidenheadToLatLon(grid)
}
