package ca.benlu.plip

import android.content.Context
import android.text.format.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Formatters are built per call so a language switch takes effect immediately.

fun formatMl(ml: Int): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(ml)

/** Short form for the status bar chip: "750 ml", "1,25 l". */
fun litres(ml: Int): String =
    if (ml < 1000) "${formatMl(ml)} ml"
    else "${DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(ml / 1000.0)} l"

private fun timeFormat() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

/** Bare time of day in the current language, e.g. "14:35" or "2:35 PM". */
fun clock(time: Long): String = timeFormat().format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

fun clock(minutes: Int): String = timeFormat().format(LocalTime.of(minutes / 60, minutes % 60))

/** Time of day as a phrase, e.g. "14:35 Uhr". */
fun Context.timeOfDay(time: Long): String = getString(R.string.time_of_day, clock(time))

fun Context.timeOfDay(minutes: Int): String = getString(R.string.time_of_day, clock(minutes))

/** "14:35 Uhr", "morgen um 08:45 Uhr", or a date with time further out. */
fun Context.whenLabel(time: Long): String {
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        LocalDate.now() -> timeOfDay(time)
        LocalDate.now().plusDays(1) -> getString(R.string.tomorrow_at, clock(time))
        else -> getString(R.string.date_at, date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), clock(time))
    }
}

fun Context.intervalLabel(minutes: Int): String = when {
    minutes == 90 -> getString(R.string.interval_90)
    minutes >= 60 && minutes % 60 == 0 -> resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)
    else -> resources.getQuantityString(R.plurals.minutes, minutes, minutes)
}

/** "Heute", "Gestern" or e.g. "Donnerstag, 29. Okt." */
fun Context.dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> getString(R.string.today)
        today.minusDays(1) -> getString(R.string.yesterday)
        else -> {
            val locale = Locale.getDefault()
            day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMM"), locale))
        }
    }
}
