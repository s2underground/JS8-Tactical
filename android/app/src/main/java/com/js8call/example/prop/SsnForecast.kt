package com.js8call.example.prop

/**
 * Smoothed monthly sunspot numbers, for VOACAP (issue #57). VOACAP is a monthly-median model and
 * takes the smoothed sunspot number for the month, not a day's value.
 *
 * Source: NOAA SWPC's solar cycle forecast (predicted-solar-cycle.json, fields "time-tag"
 * yyyy-mm and "predicted_ssn"), fetched with the other NOAA data and kept, so a station that goes
 * off-grid still has it for years ahead. Without it, the month's number is estimated from the
 * solar flux the Prop tab already has (typed in, relayed or last fetched).
 */
object SsnForecast {

    /** Where a sunspot number came from, for the label under a prediction. */
    enum class Source { NOAA_FORECAST, FROM_FLUX, FROM_MANUAL_FLUX }

    data class Value(val ssn: Double, val source: Source)

    private val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
    private val OBJECT = Regex("\\{[^{}]*\\}")
    private val TAG = Regex("\"time-tag\"\\s*:\\s*\"([^\"]*)\"")
    private val SSN = Regex("\"predicted_ssn\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")

    /**
     * Months and their predicted smoothed sunspot number from NOAA's JSON. Entries that are
     * missing, malformed or outside 0..400 (NOAA uses -1 for "not available") are skipped.
     */
    fun parse(json: String): Map<String, Double> {
        val out = sortedMapOf<String, Double>()
        for (m in OBJECT.findAll(json)) {
            val tag = TAG.find(m.value)?.groupValues?.get(1) ?: continue
            if (!MONTH.matches(tag)) continue
            val ssn = SSN.find(m.value)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (ssn in 0.0..400.0) out[tag] = ssn
        }
        return out
    }

    fun encode(months: Map<String, Double>): String = months.entries.joinToString("\n") { "${it.key},${it.value}" }

    fun decode(text: String): Map<String, Double> = text.lines().mapNotNull { line ->
        val p = line.split(',')
        if (p.size != 2 || !MONTH.matches(p[0])) return@mapNotNull null
        p[1].toDoubleOrNull()?.takeIf { it in 0.0..400.0 }?.let { p[0] to it }
    }.toMap()

    fun monthKey(year: Int, month: Int) = String.format(java.util.Locale.US, "%04d-%02d", year, month)

    /**
     * Solar numbers the operator typed in win: they are how to ask "what if". Otherwise the
     * forecast for [year]-[month] if there is one, else an estimate from [wx]'s flux.
     */
    fun choose(forecast: Map<String, Double>, year: Int, month: Int, wx: SpaceWeather): Value {
        val fromFlux = wx.ssn.coerceIn(0.0, 400.0)
        if (wx.source == SpaceWeatherRepository.SOURCE_MANUAL) return Value(fromFlux, Source.FROM_MANUAL_FLUX)
        return forecast[monthKey(year, month)]?.let { Value(it, Source.NOAA_FORECAST) }
            ?: Value(fromFlux, Source.FROM_FLUX)
    }
}
