package com.js8call.example.prop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsnForecastTest {
    // Shape of NOAA's predicted-solar-cycle.json (fields per swpc.noaa.gov product page).
    private val sample = """
        [{"time-tag":"2026-11","predicted_ssn":92.4,"high_ssn":110.1,"low_ssn":75.0,"predicted_f10.7":140.2},
         {"time-tag":"2026-12","predicted_ssn":89.0,"high_ssn":107.0,"low_ssn":71.3,"predicted_f10.7":137.9},
         {"time-tag":"2027-01","predicted_ssn":-1.0},
         {"time-tag":"2027-13","predicted_ssn":50},
         {"time-tag":"2027-02"},
         {"time-tag":"2030-12","predicted_ssn":8.5}]
    """.trimIndent()

    @Test fun parsesMonthsAndSkipsJunk() {
        val m = SsnForecast.parse(sample)
        assertEquals(mapOf("2026-11" to 92.4, "2026-12" to 89.0, "2030-12" to 8.5), m)
    }

    @Test fun encodeDecodeRoundTrip() {
        val m = SsnForecast.parse(sample)
        assertEquals(m, SsnForecast.decode(SsnForecast.encode(m)))
        assertTrue(SsnForecast.decode("garbage\n2026-11,abc\n2026-11,500").isEmpty())
    }

    @Test fun forecastWinsOverFluxUnlessTheOperatorTypedNumbers() {
        val m = SsnForecast.parse(sample)
        val live = SpaceWeather(150.0, 2.0, 0L, SpaceWeatherRepository.SOURCE_NOAA)
        assertEquals(SsnForecast.Value(92.4, SsnForecast.Source.NOAA_FORECAST), SsnForecast.choose(m, 2026, 11, live))
        val noMonth = SsnForecast.choose(m, 2028, 6, live)
        assertEquals(SsnForecast.Source.FROM_FLUX, noMonth.source)
        assertEquals(live.ssn, noMonth.ssn, 1e-9)
        val manual = live.copy(source = SpaceWeatherRepository.SOURCE_MANUAL)
        assertEquals(SsnForecast.Source.FROM_MANUAL_FLUX, SsnForecast.choose(m, 2026, 11, manual).source)
    }
}
