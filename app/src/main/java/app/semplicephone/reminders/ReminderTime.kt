package app.semplicephone.reminders

import app.semplicephone.model.Reminder
import java.time.LocalTime
import java.time.ZonedDateTime

/** Calcolo puro del prossimo orario (testabile su JVM, nessuna dipendenza Android). */
object ReminderTime {

    fun parse(time: String): LocalTime? = runCatching {
        val (h, m) = time.split(':').map { it.trim().toInt() }
        LocalTime.of(h, m)
    }.getOrNull()

    /** Prossima occorrenza strettamente dopo [now], rispettando i giorni (ISO 1=lun…7=dom). */
    fun parseDate(date: String): java.time.LocalDate? = runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull()

    fun next(r: Reminder, now: ZonedDateTime): ZonedDateTime? {
        val t = parse(r.time) ?: return null
        // Promemoria di un solo giorno: quell'occorrenza, se non è già passata.
        if (r.date.isNotBlank()) {
            val d = parseDate(r.date) ?: return null
            return d.atTime(t).atZone(now.zone).takeIf { it.isAfter(now) }
        }
        val days = r.days.filter { it in 1..7 }.toSet()
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (days.isNotEmpty() && day.dayOfWeek.value !in days) continue
            // atZone gestisce i cambi d'ora: un orario inesistente viene spostato in avanti.
            val candidate = day.atTime(t).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    /** Riassunto dei giorni: [everyDay] / [weekdays] già tradotti, gli altri abbreviati in [locale]. */
    fun daysSummary(days: List<Int>, everyDay: String, weekdays: String, locale: java.util.Locale): String {
        val d = days.filter { it in 1..7 }.distinct().sorted()
        if (d.isEmpty() || d.size == 7) return everyDay
        if (d == listOf(1, 2, 3, 4, 5)) return weekdays
        return d.joinToString(", ") {
            java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, locale).trimEnd('.')
        }
    }

    /** Iniziale del giorno (1 = lunedì) nella lingua [locale]: L M M G V S D, M T W T F S S… */
    fun dayInitial(day: Int, locale: java.util.Locale): String =
        java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
}
