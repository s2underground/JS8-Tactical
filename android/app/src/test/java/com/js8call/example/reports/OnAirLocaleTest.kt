package com.js8call.example.reports

import com.js8call.example.nets.NetSchedule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Text the app sends on the air must not depend on the phone's language. Under Arabic,
 * Persian, Bengali, Marathi, Nepali or Burmese, String.format writes digits (and the minus
 * sign) in that script, which JS8 can't send and other stations can't read.
 */
class OnAirLocaleTest {
    private lateinit var saved: Locale

    @Before fun save() { saved = Locale.getDefault() }
    @After fun restore() { Locale.setDefault(saved) }

    private val locales = listOf("ar", "ar-EG", "fa-IR", "bn-BD", "mr-IN", "ne-NP", "my-MM")
    private val ascii = Regex("^[\\x20-\\x7E]*$")

    private fun eachLocale(check: (String) -> Unit) = locales.forEach { tag ->
        Locale.setDefault(Locale.forLanguageTag(tag))
        check(tag)
    }

    @Test
    fun dateTimeGroupUsesPlainDigits() = eachLocale { tag ->
        val dtg = Reports.dtg(1_795_535_255_036L)
        assertTrue("$tag: $dtg", Regex("^[0-9]{6}Z$").matches(dtg))
    }

    @Test
    fun mgrsUsesPlainDigits() = eachLocale { tag ->
        listOf(1, 2, 3, 4, 5).forEach { digits ->
            val mgrs = Geo.latLonToMgrs(LatLon(38.8977, -77.0365), digits)!!
            assertTrue("$tag/$digits: $mgrs", ascii.matches(mgrs))
            assertTrue("$tag/$digits: $mgrs reads back", Geo.mgrsToLatLon(mgrs) != null)
        }
    }

    @Test
    fun netTimesUsePlainDigits() = eachLocale { tag ->
        val t = NetSchedule.hhmm(19 * 60 + 5)
        assertTrue("$tag: $t", t == "1905")
    }
}
