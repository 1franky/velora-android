package mx.velora.android

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import mx.velora.android.ui.Change
import mx.velora.android.ui.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los mismos casos que `velora-web/src/lib/format/units.test.ts`. */
class FormatsTest {

    @Test
    fun distancesElevationAndSpeedLikeTheWeb() {
        assertEquals("42.4 km", Formats.distance(42_350.0))
        assertEquals("1,234.6 km", Formats.distance(1_234_567.0))
        assertEquals("0.0 km", Formats.distance(0.0))
        assertEquals("920 m", Formats.elevation(920.4))
        assertEquals("14.1 km/h", Formats.speed(3.92))
    }

    @Test
    fun aMissingValueIsNeverZero() {
        assertEquals("—", Formats.distance(null))
        assertEquals("—", Formats.elevation(null))
        assertEquals("—", Formats.speed(null))
        assertEquals("—", Formats.duration(null))
        assertEquals("—", Formats.date(null))
    }

    @Test
    fun durationsAsHoursMinutesSeconds() {
        assertEquals("3:00:00", Formats.duration(10_800))
        assertEquals("1:02:05", Formats.duration(3_725))
        assertEquals("0:00:45", Formats.duration(45))
        assertEquals("30:01:01", Formats.duration(30L * 3600 + 61))
    }

    @Test
    fun datesInMexicanSpanish() {
        assertEquals("08/11/2026", Formats.date(LocalDate.of(2026, 11, 8)))
        assertTrue(Formats.weekdayDate(LocalDate.of(2026, 11, 9)).matches(Regex("^lun,? 9 (de )?nov (de )?2026$")))
        assertEquals("nov", Formats.shortMonth(YearMonth.of(2026, 11)))
        assertEquals("noviembre de 2026", Formats.longMonth(YearMonth.of(2026, 11)))
        assertEquals("10/11/2026 08:05", Formats.dateTime(1794319500000L, ZoneId.of("America/Mexico_City")))
    }

    @Test
    fun changesAgainstThePreviousPeriod() {
        assertEquals(Change.Up(0.12), roundRatio(Change.between(112.0, 100.0)))
        assertEquals(Change.Down(0.75), roundRatio(Change.between(25.0, 100.0)))
        assertEquals(Change.Same, Change.between(100.2, 100.0))
        assertEquals(Change.Same, Change.between(0.0, 0.0))
        assertEquals(Change.FromZero, Change.between(40.0, 0.0))
        assertEquals(Change.None, Change.between(40.0, null))
        assertTrue(Formats.percent(0.12).startsWith("12"))
    }

    private fun roundRatio(change: Change) = when (change) {
        is Change.Up -> Change.Up(Math.round(change.ratio * 100) / 100.0)
        is Change.Down -> Change.Down(Math.round(change.ratio * 100) / 100.0)
        else -> change
    }
}
