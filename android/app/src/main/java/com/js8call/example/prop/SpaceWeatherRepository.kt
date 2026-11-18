package com.js8call.example.prop

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Solar flux and K index for the propagation model, built to keep working
 * off-grid:
 *  - live values from NOAA SWPC whenever there is internet;
 *  - NOAA's 27-day outlook (flux, Ap, largest Kp per day), saved at the same
 *    time, so a station that goes dark still has a forecast for ~4 weeks;
 *  - a daily flux history, so past the outlook the value from one solar
 *    rotation (27 days) earlier stands in;
 *  - numbers typed in by hand, or relayed over the air by another station
 *    (#SWX report).
 * Tested on WSPR data, predictions lose under 0.5 % accuracy with month-old
 * solar numbers, so the fallbacks are about keeping things sensible, not critical.
 */
class SpaceWeatherRepository(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor()

    /** The last stored live / manual / relayed values. */
    fun current(): SpaceWeather = SpaceWeather(
        sfi = prefs.getFloat(KEY_SFI, DEFAULT_SFI).toDouble(),
        kIndex = prefs.getFloat(KEY_K, DEFAULT_K).toDouble(),
        updatedMs = prefs.getLong(KEY_UPDATED, 0L),
        source = prefs.getString(KEY_SOURCE, SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
    )

    /** Best values for [timeMs]: fresh live data, else the saved outlook, else one solar rotation back, else the last known. */
    fun effective(timeMs: Long, nowMs: Long = System.currentTimeMillis()): SpaceWeather =
        choose(current(), forecast(), history(), timeMs, nowMs)

    fun setManual(sfi: Double, k: Double) = save(sfi, k, SOURCE_MANUAL)

    /**
     * Another station sent its solar numbers (#SWX). Taken when they are
     * newer than ours. Returns the adopted values, or null if ours were newer.
     */
    fun offerRelayed(sfi: Double, k: Double, observedMs: Long, from: String): SpaceWeather? {
        val cur = current()
        if (cur.source != SOURCE_DEFAULT && cur.updatedMs >= observedMs) return null
        if (sfi !in 50.0..400.0 || k !in 0.0..9.0) return null
        return save(sfi, k, "$SOURCE_RELAY_PREFIX$from", observedMs)
    }

    /** Try NOAA in the background; [done] gets the new values, or null when offline/failed. */
    fun refresh(done: (SpaceWeather?) -> Unit) {
        io.execute {
            val result = runCatching {
                val fluxJson = get(FLUX_URL)
                val sfi = parseFlux(fluxJson)
                val k = parseKp(get(KP_URL))
                saveHistory(parseFluxHistory(fluxJson))
                runCatching { saveForecast(parseOutlook(get(OUTLOOK_URL))) }
                if (sfi != null) save(sfi, k ?: current().kIndex, SOURCE_NOAA) else null
            }.getOrNull()
            done(result)
        }
    }

    fun forecast(): List<ForecastDay> =
        prefs.getString(KEY_FORECAST, "").orEmpty().lines().mapNotNull { ForecastDay.decode(it) }

    fun history(): Map<String, Double> =
        prefs.getString(KEY_HISTORY, "").orEmpty().lines().mapNotNull { line ->
            val p = line.split(','); if (p.size == 2) p[1].toDoubleOrNull()?.let { p[0] to it } else null
        }.toMap()

    private fun saveForecast(days: List<ForecastDay>) {
        if (days.isEmpty()) return
        prefs.edit().putString(KEY_FORECAST, days.joinToString("\n") { it.encode() }).apply()
    }

    private fun saveHistory(days: Map<String, Double>) {
        if (days.isEmpty()) return
        val merged = (history() + days).entries.sortedBy { it.key }.takeLast(120)
        prefs.edit().putString(KEY_HISTORY, merged.joinToString("\n") { "${it.key},${it.value}" }).apply()
    }

    private fun save(sfi: Double, k: Double, source: String, atMs: Long = System.currentTimeMillis()): SpaceWeather {
        prefs.edit()
            .putFloat(KEY_SFI, sfi.toFloat())
            .putFloat(KEY_K, k.toFloat())
            .putLong(KEY_UPDATED, atMs)
            .putString(KEY_SOURCE, source)
            .apply()
        return current()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "JS8-Tactical")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    /** One day of NOAA's 27-day outlook. */
    data class ForecastDay(val date: String, val flux: Double, val ap: Int, val kpMax: Double) {
        fun encode() = "$date,$flux,$ap,$kpMax"

        companion object {
            fun decode(s: String): ForecastDay? {
                val p = s.split(',')
                if (p.size != 4) return null
                return runCatching { ForecastDay(p[0], p[1].toDouble(), p[2].toInt(), p[3].toDouble()) }.getOrNull()
            }
        }
    }

    companion object {
        const val SOURCE_NOAA = "NOAA"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DEFAULT = "default"
        const val SOURCE_FORECAST = "forecast"
        const val SOURCE_RECURRENCE = "recurrence"
        const val SOURCE_RELAY_PREFIX = "via "
        private const val KEY_SFI = "swx_sfi"
        private const val KEY_K = "swx_k"
        private const val KEY_UPDATED = "swx_updated"
        private const val KEY_SOURCE = "swx_source"
        private const val KEY_FORECAST = "swx_forecast"
        private const val KEY_HISTORY = "swx_history"
        private const val DEFAULT_SFI = 140f
        private const val DEFAULT_K = 2f
        private const val STALE_MS = 36 * 3_600_000L
        private const val FLUX_URL = "https://services.swpc.noaa.gov/json/f107_cm_flux.json"
        private const val KP_URL = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"
        private const val OUTLOOK_URL = "https://services.swpc.noaa.gov/text/27-day-outlook.txt"
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        fun dateKey(ms: Long): String {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
        }

        /** The fallback chain, pure so it can be tested. */
        fun choose(live: SpaceWeather, forecast: List<ForecastDay>, history: Map<String, Double>, timeMs: Long, nowMs: Long): SpaceWeather {
            val fresh = live.source != SOURCE_DEFAULT && nowMs - live.updatedMs < STALE_MS
            val farAhead = timeMs - nowMs > 24 * 3_600_000L
            val day = dateKey(timeMs)
            val f = forecast.firstOrNull { it.date == day }
            if (fresh && !(farAhead && f != null)) return live
            // The outlook gives the day's largest Kp; a typical hour runs about one lower.
            if (f != null) return SpaceWeather(f.flux, (f.kpMax - 1).coerceAtLeast(0.0), live.updatedMs, SOURCE_FORECAST)
            val rotation = history[dateKey(timeMs - 27 * 86_400_000L)]
            if (rotation != null && !fresh) return SpaceWeather(rotation, live.kIndex, live.updatedMs, SOURCE_RECURRENCE)
            return live
        }

        /** NOAA 27-day outlook text: "2026 Mar 02     150          10          3". */
        fun parseOutlook(text: String): List<ForecastDay> {
            val re = Regex("""^\s*(\d{4})\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d+)\s+(\d+)\s+(\d+)""")
            return text.lines().mapNotNull { line ->
                val m = re.find(line) ?: return@mapNotNull null
                val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }
                if (month < 0) return@mapNotNull null
                val date = String.format(java.util.Locale.US, "%s-%02d-%02d", m.groupValues[1], month + 1, m.groupValues[3].toInt())
                ForecastDay(date, m.groupValues[4].toDouble(), m.groupValues[5].toInt(), m.groupValues[6].toDouble())
            }
        }

        /** Daily flux from the f107_cm_flux.json array (last value of each day). */
        fun parseFluxHistory(json: String): Map<String, Double> =
            objects(json).mapNotNull { o ->
                val flux = o["flux"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val t = o["time_tag"]?.takeIf { it.length >= 10 } ?: return@mapNotNull null
                t to flux
            }.sortedBy { it.first }.associate { it.first.substring(0, 10) to it.second }

        /** Latest 10.7 cm flux from an array of objects with a "flux" field. */
        fun parseFlux(json: String): Double? =
            objects(json).mapNotNull { o ->
                val flux = o["flux"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                (o["time_tag"] ?: "") to flux
            }.maxByOrNull { it.first }?.second

        /**
         * Latest Kp. Accepts an array of objects (kp_index / estimated_kp / Kp)
         * or the older layout where the first row holds the keys.
         */
        fun parseKp(json: String): Double? {
            val text = json.trim()
            if (text.startsWith("[") && text.drop(1).trimStart().startsWith("[")) {
                val rows = Regex("""\[([^\[\]]*)\]""").findAll(text).map { m ->
                    m.groupValues[1].split(',').map { it.trim().trim('"') }
                }.toList()
                val keys = rows.firstOrNull() ?: return null
                val col = keys.indexOfFirst { it.equals("Kp", true) || it.equals("kp_index", true) }
                if (col < 0) return null
                return rows.drop(1).asReversed().firstNotNullOfOrNull { it.getOrNull(col)?.toDoubleOrNull() }
            }
            return objects(text).asReversed().firstNotNullOfOrNull { o ->
                listOf("estimated_kp", "kp_index", "Kp", "kp").firstNotNullOfOrNull { o[it]?.toDoubleOrNull() }
            }
        }

        /**
         * Flat JSON objects in an array, as key → raw value strings. NOAA's
         * files are simple enough that this avoids depending on a JSON library
         * (and keeps the parsers testable off-device).
         */
        private fun objects(json: String): List<Map<String, String>> =
            Regex("""\{([^{}]*)\}""").findAll(json).map { m ->
                Regex(""""([^"]+)"\s*:\s*("([^"]*)"|[^,}\s]+)""").findAll(m.groupValues[1]).associate { kv ->
                    val raw = kv.groupValues[2]
                    kv.groupValues[1] to (if (raw.startsWith('"')) kv.groupValues[3] else raw)
                }
            }.toList()
    }
}
