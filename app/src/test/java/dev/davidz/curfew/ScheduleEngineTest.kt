package dev.davidz.curfew

import dev.davidz.curfew.core.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEngineTest {

    private val start = 23 * 60 // 23:00
    private val end = 7 * 60    // 07:00

    @Test
    fun `wrapping window covers the night`() {
        assertTrue(ScheduleEngine.isInside(23 * 60, start, end))
        assertTrue(ScheduleEngine.isInside(0, start, end))
        assertTrue(ScheduleEngine.isInside(3 * 60 + 17, start, end))
        assertTrue(ScheduleEngine.isInside(6 * 60 + 59, start, end))
    }

    @Test
    fun `wrapping window excludes the day`() {
        assertFalse(ScheduleEngine.isInside(7 * 60, start, end))
        assertFalse(ScheduleEngine.isInside(12 * 60, start, end))
        assertFalse(ScheduleEngine.isInside(22 * 60 + 59, start, end))
    }

    @Test
    fun `non wrapping window behaves normally`() {
        assertTrue(ScheduleEngine.isInside(10 * 60, 9 * 60, 17 * 60))
        assertFalse(ScheduleEngine.isInside(8 * 60, 9 * 60, 17 * 60))
        assertFalse(ScheduleEngine.isInside(17 * 60, 9 * 60, 17 * 60))
    }

    @Test
    fun `equal endpoints mean no window, never all day`() {
        assertFalse(ScheduleEngine.isInside(0, 60, 60))
        assertFalse(ScheduleEngine.isInside(60, 60, 60))
        assertEquals(0, ScheduleEngine.windowLength(60, 60))
    }

    @Test
    fun `minutes until end counts across midnight`() {
        assertEquals(8 * 60, ScheduleEngine.minutesUntilEnd(23 * 60, start, end))
        assertEquals(7 * 60, ScheduleEngine.minutesUntilEnd(0, start, end))
        assertEquals(1, ScheduleEngine.minutesUntilEnd(6 * 60 + 59, start, end))
        assertEquals(0, ScheduleEngine.minutesUntilEnd(12 * 60, start, end))
    }

    @Test
    fun `minutes until start counts forward`() {
        assertEquals(11 * 60, ScheduleEngine.minutesUntilStart(12 * 60, start, end))
        assertEquals(16 * 60, ScheduleEngine.minutesUntilStart(7 * 60, start, end))
        assertEquals(0, ScheduleEngine.minutesUntilStart(2 * 60, start, end))
    }

    @Test
    fun `window length is the night length`() {
        assertEquals(8 * 60, ScheduleEngine.windowLength(start, end))
        assertEquals(8 * 60, ScheduleEngine.windowLength(9 * 60, 17 * 60))
    }

    @Test
    fun `out of range minutes wrap instead of throwing`() {
        assertTrue(ScheduleEngine.isInside(24 * 60, start, end))   // 24:00 == 00:00
        assertTrue(ScheduleEngine.isInside(-60, start, end))       // -01:00 == 23:00
    }

    @Test
    fun `formatting`() {
        assertEquals("23:00", ScheduleEngine.format(23 * 60))
        assertEquals("07:05", ScheduleEngine.format(7 * 60 + 5))
        assertEquals("00:00", ScheduleEngine.format(24 * 60))
        assertEquals("4h 12m", ScheduleEngine.formatDuration(252))
        assertEquals("3h", ScheduleEngine.formatDuration(180))
        assertEquals("12m", ScheduleEngine.formatDuration(12))
        assertEquals("now", ScheduleEngine.formatDuration(0))
    }
}
