package io.github.aleksandarrado93.paywall.android

import io.github.aleksandarrado93.paywall.Period
import io.github.aleksandarrado93.paywall.PeriodUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IsoPeriodTest {

    @Test
    fun parsesPlayPeriods() {
        assertEquals(Period(3, PeriodUnit.DAY), parseIsoPeriod("P3D"))
        assertEquals(Period(1, PeriodUnit.WEEK), parseIsoPeriod("P1W"))
        assertEquals(Period(6, PeriodUnit.MONTH), parseIsoPeriod("P6M"))
        assertEquals(Period(1, PeriodUnit.YEAR), parseIsoPeriod("P1Y"))
        assertNull(parseIsoPeriod("garbage"))
        assertNull(parseIsoPeriod("P"))
    }
}
