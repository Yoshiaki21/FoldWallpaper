package com.yoshiaki21.FoldWallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchIntervalTest {

    @Test
    fun `none does not switch on elapsed time`() {
        assertFalse(SwitchInterval.NONE.isTimeBased)
        assertEquals(0L, SwitchInterval.NONE.millis)
    }

    @Test
    fun `timed intervals convert minutes to milliseconds`() {
        assertTrue(SwitchInterval.MINUTES_15.isTimeBased)
        assertEquals(15 * 60_000L, SwitchInterval.MINUTES_15.millis)
        assertEquals(60 * 60_000L, SwitchInterval.HOUR_1.millis)
        assertEquals(360 * 60_000L, SwitchInterval.HOURS_6.millis)
    }

    @Test
    fun `round trips through the persisted minute value`() {
        SwitchInterval.entries.forEach { interval ->
            assertEquals(interval, SwitchInterval.fromMinutes(interval.minutes))
        }
    }

    @Test
    fun `an unknown persisted value falls back to the default`() {
        assertEquals(SwitchInterval.DEFAULT, SwitchInterval.fromMinutes(7))
        assertEquals(SwitchInterval.DEFAULT, SwitchInterval.fromMinutes(-1))
    }
}
