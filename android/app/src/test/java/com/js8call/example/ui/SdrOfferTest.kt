package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdrOfferTest {
    @Test fun airspyIsRecognised() {
        assertEquals("airspy_hf", SdrOffer.classify(0x03EB, 0x800C)?.rigType)
    }

    @Test fun rtlSdrBlogV3IsRecognised() {
        // RTL2832U (0bda:2838), the RTL-SDR Blog V3's ID.
        assertEquals("rtl_sdr", SdrOffer.classify(0x0BDA, 0x2838)?.rigType)
    }

    @Test fun aRadioSoundCardIsNotAnSdr() {
        // C-Media CM108 (Digirig audio) and a CP2102 serial port.
        assertNull(SdrOffer.classify(0x0D8C, 0x0012))
        assertNull(SdrOffer.classify(0x10C4, 0xEA60))
    }
}
