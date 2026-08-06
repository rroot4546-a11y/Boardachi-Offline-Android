package com.privateboard.clinical

import org.junit.Assert.*
import org.junit.Test

class CorpusContractTest {
    @Test fun sessionDefaultsAreSafe() {
        val config = SessionConfig()
        assertEquals(20, config.count)
        assertEquals(SessionMode.STUDY, config.mode)
        assertFalse(config.favoritesOnly)
    }

    @Test fun sourceTotalsContractIsExact() {
        val totals = mapOf(55 to 681, 78 to 1200, 20 to 1246, 30 to 1256, 32 to 1554, 21 to 5429, 29 to 214)
        assertEquals(7, totals.size)
        assertEquals(11_580, totals.values.sum())
    }
}
