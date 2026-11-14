package io.embodit.kinetica.domain

import io.embodit.kinetica.data.PrefsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefsTest {
    @Test fun stationsRoundTrip() {
        val s = listOf(StationPref(Hanger.DOUBLE, 0, listOf(BobMat.BRASS, BobMat.WOOD)), StationPref(Hanger.ROD, 2, listOf(BobMat.WOOD)), StationPref(Hanger.SPRING, 1, listOf(BobMat.STEEL)))
        assertEquals(s, PrefsCodec.parseStations(PrefsCodec.stations(s)))
        assertEquals(Settings.DEFAULT_STATIONS, PrefsCodec.parseStations(PrefsCodec.stations(Settings.DEFAULT_STATIONS)))
        assertNull(PrefsCodec.parseStations("ROD,1,STEEL"))
        assertNull(PrefsCodec.parseStations(null))
        assertNull(PrefsCodec.parseStations("ROD,1,STEEL+WOOD;ROD,1,STEEL;ROD,1,STEEL"))
    }
}
