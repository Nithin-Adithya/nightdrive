package com.nithin.nightdrive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.CancellationSignal
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.json.JSONObject

/** Outside temperature and conditions for the sidebar and Weather widget, from the free Open-Meteo service (no account or key). */
object Weather {

    class Report(
        val temperature: Int, val code: Int, val day: Boolean, val high: Int?, val low: Int?
    ) {
        /** Text and icon for the WMO weather code. */
        val condition: Int
            get() = when (code) {
                0 -> R.string.wx_clear
                1, 2 -> R.string.wx_partly
                3 -> R.string.wx_cloudy
                45, 48 -> R.string.wx_fog
                in 51..57 -> R.string.wx_drizzle
                in 71..77, 85, 86 -> R.string.wx_snow
                in 95..99 -> R.string.wx_storm
                else -> R.string.wx_rain
            }
        val icon: Int
            get() = when (code) {
                0, 1 -> if (day) R.drawable.ic_wx_sun else R.drawable.ic_wx_moon
                2, 3, 45, 48, in 71..77, 85, 86 -> R.drawable.ic_wx_cloud
                in 95..99 -> R.drawable.ic_wx_storm
                else -> R.drawable.ic_wx_rain
            }
    }

    /**
     * Blocking; call off the main thread. Returns current conditions in degrees Celsius, or null
     * when there is no location permission, no known position or no answer. Only a position
     * rounded to about 10 km is sent.
     */
    fun report(context: Context): Report? {
        val location = lastLocation(context) ?: return null
        val latitude = "%.1f".format(Locale.US, location.latitude)
        val longitude = "%.1f".format(Locale.US, location.longitude)
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode != 200) {
                null
            } else {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val current = json.getJSONObject("current")
                val daily = json.optJSONObject("daily")
                Report(
                    temperature = current.getDouble("temperature_2m").roundToInt(),
                    code = current.optInt("weather_code", 3),
                    day = current.optInt("is_day", 1) == 1,
                    high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeUnless { it.isNaN() }?.roundToInt(),
                    low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeUnless { it.isNaN() }?.roundToInt()
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun hasLocationPermission(context: Context) = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun lastLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?: freshLocation(manager)
        } catch (e: SecurityException) {
            null
        }
    }

    /** Nothing has asked for a position since boot, so ask once and wait briefly for the answer. */
    private fun freshLocation(manager: LocationManager): Location? {
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return null
        val answer = AtomicReference<Location?>()
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        // Whichever source answers first wins; a source with no fix answers null, late or never.
        // The callback only records the answer, so it runs inline: the system may still deliver
        // one after we stop waiting, and must not find a shut-down executor.
        val record = { fix: Location? ->
            if (fix != null) {
                answer.compareAndSet(null, fix)
                done.countDown()
            }
        }
        val legacy = mutableListOf<LocationListener>()
        for (provider in providers) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(provider, cancel, Executor { it.run() }) { record(it) }
            } else {
                // Android 10 and earlier (many head units are on 8.1): one-shot update instead.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = record(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                legacy += listener
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
        done.await(15, TimeUnit.SECONDS)
        cancel.cancel()
        legacy.forEach(manager::removeUpdates)
        return answer.get()
    }
}
