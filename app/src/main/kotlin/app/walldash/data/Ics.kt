package app.walldash.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * iCalendar（.ics）の予定を読む。CalDAV の応答と、共有カレンダーの公開 URL の両方で使う。
 *
 * 繰り返し（RRULE）は壁に出す範囲で要る分だけ扱う: FREQ（DAILY / WEEKLY / MONTHLY / YEARLY）、INTERVAL、COUNT、UNTIL、
 * BYDAY（毎週の曜日、毎月の「第 2 月曜」「最終金曜」）、BYMONTHDAY、BYMONTH。除外日（EXDATE）と、
 * 1 回だけ変えた予定（RECURRENCE-ID）も反映する。
 */
object Ics {

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class When(val local: LocalDateTime, val zone: ZoneId, val allDay: Boolean) {
        fun ms(): Long = local.atZone(zone).toInstant().toEpochMilli()
    }

    fun events(text: String, from: Long, to: Long, deviceZone: ZoneId, calendar: String?, color: String?): List<CalendarEvent> {
        val vevents = parse(text)
        // 1 回だけ変えた予定: UID ごとに「元の回の開始時刻」を覚え、繰り返しの側ではその回を飛ばす
        val overridden = mutableMapOf<String, MutableSet<Long>>()
        vevents.forEach { ev ->
            val rid = ev["RECURRENCE-ID"] ?: return@forEach
            val uid = ev["UID"]?.value ?: return@forEach
            parseWhen(rid, deviceZone)?.let { overridden.getOrPut(uid) { mutableSetOf() } += it.ms() }
        }
        val out = mutableListOf<CalendarEvent>()
        vevents.forEach { ev ->
            if (ev["STATUS"]?.value.equals("CANCELLED", ignoreCase = true)) return@forEach
            val start = ev["DTSTART"]?.let { parseWhen(it, deviceZone) } ?: return@forEach
            val length = length(ev, start, deviceZone)
            val title = unescape(ev["SUMMARY"]?.value ?: "（件名なし）")
            val rrule = ev["RRULE"]?.value
            val starts = if (rrule == null || ev["RECURRENCE-ID"] != null) {
                listOf(start.local)
            } else {
                val skip = ev.all("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { parseWhen(p.copy(value = it), deviceZone)?.ms() } }.toSet() +
                    overridden[ev["UID"]?.value].orEmpty()
                expand(rrule, start, to, deviceZone).filter { it.atZone(start.zone).toInstant().toEpochMilli() !in skip }
            }
            starts.forEach { local ->
                val s = local.atZone(start.zone).toInstant().toEpochMilli()
                val e = local.plus(length).atZone(start.zone).toInstant().toEpochMilli()
                if (e > from && s < to || s in from until to) {
                    out += CalendarEvent(title, s, maxOf(e, s), start.allDay, calendar, color)
                }
            }
        }
        return out.sortedWith(compareBy({ it.start }, { !it.allDay }))
    }

    // ------------------------------------------------------------ 読み取り

    private class VEvent(val props: List<Prop>) {
        operator fun get(name: String) = props.firstOrNull { it.name == name }
        fun all(name: String) = props.filter { it.name == name }
    }

    private fun parse(text: String): List<VEvent> {
        // 75 文字で折り返された行（次の行が空白で始まる）をつなぐ
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val events = mutableListOf<VEvent>()
        var current: MutableList<Prop>? = null
        // VEVENT の中の VALARM などは読み飛ばす
        var nested = 0
        for (line in lines) {
            val props = current
            when {
                line == "BEGIN:VEVENT" -> { current = mutableListOf(); nested = 0 }
                props == null -> {}
                line == "END:VEVENT" -> { events += VEvent(props); current = null }
                line.startsWith("BEGIN:") -> nested++
                line.startsWith("END:") -> nested--
                nested == 0 -> parseProp(line)?.let { props += it }
            }
        }
        return events
    }

    private fun parseProp(line: String): Prop? {
        val colon = findColon(line).takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1].trim('"') } }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    /** 引用符の中のコロン（TZID="…:…" など）は区切りとみなさない。 */
    private fun findColon(line: String): Int {
        var quoted = false
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return i
        }
        return -1
    }

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private fun parseWhen(p: Prop, deviceZone: ZoneId): When? = runCatching {
        val v = p.value.trim()
        if (p.params["VALUE"] == "DATE" || v.length == 8) {
            When(LocalDate.parse(v.take(8), DATE).atStartOfDay(), deviceZone, true)
        } else if (v.endsWith("Z")) {
            val utc = LocalDateTime.parse(v.dropLast(1), DATE_TIME)
            When(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(deviceZone).toLocalDateTime(), deviceZone, false)
        } else {
            val zone = p.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
            When(LocalDateTime.parse(v.take(15), DATE_TIME), zone, false)
        }
    }.getOrNull()

    private fun length(ev: VEvent, start: When, deviceZone: ZoneId): Duration {
        ev["DTEND"]?.let { parseWhen(it, deviceZone) }?.let { end ->
            return Duration.between(start.local.atZone(start.zone), end.local.atZone(end.zone)).coerceAtLeast(Duration.ZERO)
        }
        ev["DURATION"]?.value?.let { d -> runCatching { return Duration.parse(d.replace(Regex("^P(\\d+)W$")) { "P${it.groupValues[1].toInt() * 7}D" }) } }
        return if (start.allDay) Duration.ofDays(1) else Duration.ZERO
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    // ------------------------------------------------------------ 繰り返し

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )

    private fun expand(rule: String, start: When, to: Long, deviceZone: ZoneId): List<LocalDateTime> {
        val r = rule.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].uppercase() to p[1] } }.toMap()
        val freq = r["FREQ"] ?: return listOf(start.local)
        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = r["COUNT"]?.toIntOrNull()
        val until = r["UNTIL"]?.let { parseWhen(Prop("UNTIL", emptyMap(), it), deviceZone) }?.ms()
        val byDay = r["BYDAY"]?.split(',')?.mapNotNull { d ->
            val m = Regex("^([+-]?\\d{1,2})?([A-Z]{2})$").find(d.trim().uppercase()) ?: return@mapNotNull null
            (m.groupValues[1].toIntOrNull()) to (DAYS[m.groupValues[2]] ?: return@mapNotNull null)
        }.orEmpty()
        val byMonthDay = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val byMonth = r["BYMONTH"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val time: LocalTime = start.local.toLocalTime()
        val first = start.local.toLocalDate()

        val out = mutableListOf<LocalDateTime>()
        var emitted = 0
        fun past(d: LocalDateTime): Boolean {
            val ms = d.atZone(start.zone).toInstant().toEpochMilli()
            return ms >= to || (until != null && ms > until)
        }
        // 1 周期（1 日・1 週・1 か月・1 年）ずつ進めて、その中の回を古い順に出す
        var period = 0L
        while (period < MAX_PERIODS) {
            val dates: List<LocalDate> = when (freq) {
                "DAILY" -> listOf(first.plusDays(period * interval))
                "WEEKLY" -> {
                    val weekStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(period * interval)
                    val days = byDay.map { it.second }.ifEmpty { listOf(first.dayOfWeek) }
                    days.map { weekStart.plusDays((it.value - 1).toLong()) }.sorted()
                }
                "MONTHLY" -> {
                    val month = first.withDayOfMonth(1).plusMonths(period * interval)
                    when {
                        byDay.isNotEmpty() -> byDay.flatMap { (n, dow) -> nthInMonth(month, n, dow) }.sorted()
                        byMonthDay.isNotEmpty() -> byMonthDay.mapNotNull { dayOfMonth(month, it) }.sorted()
                        else -> listOfNotNull(dayOfMonth(month, first.dayOfMonth))
                    }
                }
                "YEARLY" -> {
                    val year = first.withDayOfYear(1).plusYears(period * interval)
                    val months = byMonth.ifEmpty { listOf(first.monthValue) }
                    months.flatMap { m ->
                        val month = year.withMonth(m)
                        when {
                            byDay.isNotEmpty() -> byDay.flatMap { (n, dow) -> nthInMonth(month, n, dow) }
                            byMonthDay.isNotEmpty() -> byMonthDay.mapNotNull { dayOfMonth(month, it) }
                            else -> listOfNotNull(dayOfMonth(month, first.dayOfMonth))
                        }
                    }.sorted()
                }
                else -> return listOf(start.local)
            }
            for (d in dates) {
                if (d.isBefore(first)) continue
                val at = LocalDateTime.of(d, time)
                if (past(at)) return out
                out += at
                emitted++
                if (count != null && emitted >= count) return out
            }
            period++
        }
        return out
    }

    /** その月の第 n の曜日（n が負なら最後から、null なら全部）。 */
    private fun nthInMonth(month: LocalDate, n: Int?, dow: DayOfWeek): List<LocalDate> {
        val all = generateSequence(month.with(TemporalAdjusters.firstInMonth(dow))) { it.plusWeeks(1) }
            .takeWhile { it.month == month.month }.toList()
        return when {
            n == null -> all
            n > 0 -> listOfNotNull(all.getOrNull(n - 1))
            else -> listOfNotNull(all.getOrNull(all.size + n))
        }
    }

    private fun dayOfMonth(month: LocalDate, day: Int): LocalDate? {
        val len = month.lengthOfMonth()
        val d = if (day < 0) len + day + 1 else day
        return if (d in 1..len) month.withDayOfMonth(d) else null
    }

    /** 毎日の予定が何十年も前から続いていても止まるように。 */
    private const val MAX_PERIODS = 20_000L
}
