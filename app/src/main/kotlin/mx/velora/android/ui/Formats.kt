package mx.velora.android.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Formatos visibles en es-MX, los mismos que la web (`velora-web/src/lib/format/units.ts`). La API entrega SI (m, s,
 * m/s); aquí se convierte a km, m y km/h. Un dato ausente se muestra como "—", nunca como cero.
 */
object Formats {
    val LOCALE: Locale = Locale.forLanguageTag("es-MX")
    const val MISSING = "—"

    private fun decimals(min: Int, max: Int) =
        NumberFormat.getNumberInstance(LOCALE).apply {
            minimumFractionDigits = min
            maximumFractionDigits = max
        }

    private val oneDecimal = decimals(1, 1)
    private val integer = decimals(0, 0)
    private val percent = NumberFormat.getPercentInstance(LOCALE).apply { maximumFractionDigits = 0 }

    fun distance(meters: Double?): String = meters?.let { "${oneDecimal.format(it / 1000)} km" } ?: MISSING

    /** Kilómetros sin decimales, para etiquetas de gráficas. */
    fun kilometersRounded(meters: Double): String = "${integer.format(meters / 1000)} km"

    fun elevation(meters: Double?): String = meters?.let { "${integer.format(it)} m" } ?: MISSING

    fun speed(metersPerSecond: Double?): String = metersPerSecond?.let { "${oneDecimal.format(it * 3.6)} km/h" } ?: MISSING

    fun count(value: Int): String = integer.format(value)

    /** h:mm:ss, también para más de un día (30:01:01). */
    fun duration(seconds: Long?): String {
        if (seconds == null) return MISSING
        val total = seconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(LOCALE, total / 3600, total % 3600 / 60, total % 60)
    }

    fun percent(ratio: Double): String = percent.format(abs(ratio))

    private val calendarDate = DateTimeFormatter.ofPattern("dd/MM/yyyy", LOCALE)
    private val weekdayDate = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", LOCALE)
    private val shortMonth = DateTimeFormatter.ofPattern("MMM", LOCALE)
    private val longMonth = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", LOCALE)
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", LOCALE)

    fun date(date: LocalDate?): String = date?.format(calendarDate) ?: MISSING

    /** "lun, 9 nov 2026": sin los puntos de abreviatura. */
    fun weekdayDate(date: LocalDate?): String = date?.format(weekdayDate)?.replace(".", "") ?: MISSING

    fun shortMonth(month: YearMonth): String = month.atDay(1).format(shortMonth).replace(".", "")

    fun longMonth(month: YearMonth): String = month.atDay(1).format(longMonth)

    /** Fecha y hora (24 h) en la zona del teléfono, p. ej. para decir de cuándo es la caché. */
    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(dateTime)

}

/** Cambio contra el periodo anterior, como en la web: menos de 0.5 % cuenta como igual. */
sealed interface Change {
    data class Up(val ratio: Double) : Change

    data class Down(val ratio: Double) : Change

    data object Same : Change

    /** El periodo anterior no tuvo nada: un porcentaje sería infinito o engañoso. */
    data object FromZero : Change

    data object None : Change

    companion object {
        fun between(current: Double, previous: Double?): Change {
            if (previous == null) return None
            if (previous == 0.0) return if (current == 0.0) Same else FromZero
            val ratio = (current - previous) / previous
            return when {
                abs(ratio) < 0.005 -> Same
                ratio > 0 -> Up(ratio)
                else -> Down(-ratio)
            }
        }
    }
}
