package com.js8call.example.prop

import android.content.Context
import androidx.preference.PreferenceManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Solar flux and K index for the propagation model. Fetched from NOAA SWPC
 * when there is internet; otherwise the last values (or values the operator
 * typed in, e.g. heard on a net) are used. Never blocks the UI.
 */
class SpaceWeatherRepository(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val io = Executors.newSingleThreadExecutor()

    fun current(): SpaceWeather = SpaceWeather(
        sfi = prefs.getFloat(KEY_SFI, DEFAULT_SFI).toDouble(),
        kIndex = prefs.getFloat(KEY_K, DEFAULT_K).toDouble(),
        updatedMs = prefs.getLong(KEY_UPDATED, 0L),
        source = prefs.getString(KEY_SOURCE, SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
    )

    fun setManual(sfi: Double, k: Double) = save(sfi, k, SOURCE_MANUAL)

    /** Flux estimated from heard traffic; K is kept. */
    fun setEstimated(sfi: Double) = save(sfi, current().kIndex, SOURCE_FIT)

    /** Try NOAA in the background; [done] gets the new values, or null when offline/failed. */
    fun refresh(done: (SpaceWeather?) -> Unit) {
        io.execute {
            val result = runCatching {
                val sfi = parseFlux(get(FLUX_URL))
                val k = parseKp(get(KP_URL))
                if (sfi != null) save(sfi, k ?: current().kIndex, SOURCE_NOAA) else null
            }.getOrNull()
            done(result)
        }
    }

    private fun save(sfi: Double, k: Double, source: String): SpaceWeather {
        prefs.edit()
            .putFloat(KEY_SFI, sfi.toFloat())
            .putFloat(KEY_K, k.toFloat())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
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

    companion object {
        const val SOURCE_NOAA = "NOAA"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DEFAULT = "default"
        const val SOURCE_FIT = "fit"
        private const val KEY_SFI = "swx_sfi"
        private const val KEY_K = "swx_k"
        private const val KEY_UPDATED = "swx_updated"
        private const val KEY_SOURCE = "swx_source"
        private const val DEFAULT_SFI = 140f
        private const val DEFAULT_K = 2f
        private const val FLUX_URL = "https://services.swpc.noaa.gov/json/f107_cm_flux.json"
        private const val KP_URL = "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json"

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
