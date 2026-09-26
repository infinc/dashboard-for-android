package app.walldash.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * カウントダウンの行事を「次はいつか」に直す（通信しない。祝日だけは内閣府の CSV を使う）。
 */
object Countdown {

    data class Target(val name: String, val at: Long, val note: String? = null)

    /** 組み込みの行事の表示名（設定画面の選択肢にも使う）。 */
    val BUILTIN_LABELS = linkedMapOf(
        "newyear" to "新年",
        "christmas" to "クリスマス",
        "holiday" to "次の祝日",
        "dayoff" to "次の休日（土日・祝日）",
        "fullmoon" to "次の満月",
        "newmoon" to "次の新月",
    )

    /**
     * 利用者が書いた日付。"2027-03-18" / "03-18"（毎年）/ どちらも後ろに " 18:30" を付けられる。
     * 読めなければ null。
     */
    data class ParsedDate(val date: LocalDate?, val monthDay: MonthDay?, val time: LocalTime?)

    fun parseDate(raw: String): ParsedDate? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts.size > 2) return null
        val time = parts.getOrNull(1)?.let { runCatching { LocalTime.parse(if (it.length == 4) "0$it" else it) }.getOrNull() ?: return null }
        val d = parts[0].replace('/', '-')
        runCatching { LocalDate.parse(d) }.getOrNull()?.let { return ParsedDate(it, null, time) }
        val md = Regex("^(\\d{1,2})-(\\d{1,2})$").find(d) ?: return null
        val monthDay = runCatching { MonthDay.of(md.groupValues[1].toInt(), md.groupValues[2].toInt()) }.getOrNull() ?: return null
        return ParsedDate(null, monthDay, time)
    }

    private val LINE = Regex("^(.+?)\\s+(\\d{1,4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?(?:\\s+\\d{1,2}:\\d{2})?)$")

    /** 設定画面の「1 行に 1 つ（名前 日付）」を行事の並びにする。読めない行は捨てる。 */
    fun parseLines(text: String): List<CountdownEvent> = text.lines().mapNotNull { line ->
        val m = LINE.find(line.trim()) ?: return@mapNotNull null
        CountdownEvent(m.groupValues[1].trim(), m.groupValues[2].trim())
    }

    fun toLines(events: List<CountdownEvent>): String = events.joinToString("\n") { "${it.name} ${it.date}" }

    /** 近い順に並べた、まだ来ていない行事。 */
    fun targets(config: CountdownConfig, holidays: List<Holiday>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Target> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun ms(date: LocalDate, time: LocalTime = LocalTime.MIDNIGHT) = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        val holidayDates = holidays.mapNotNull { h -> runCatching { LocalDate.parse(h.date) to h.name }.getOrNull() }.toMap()
        val moon by lazy { Astro.moon(now) }

        val list = mutableListOf<Target>()
        config.builtins.forEach { key ->
            when (key) {
                "newyear" -> list += Target("新年", ms(LocalDate.of(today.year + 1, 1, 1)), "${today.year + 1}年 1月1日")
                "christmas" -> {
                    val thisYear = LocalDate.of(today.year, 12, 25)
                    val d = if (today.isAfter(thisYear)) thisYear.plusYears(1) else thisYear
                    list += Target("クリスマス", ms(d), "12月25日")
                }
                "holiday" -> holidayDates.keys.filter { it.isAfter(today) }.minOrNull()?.let { d ->
                    list += Target(holidayDates.getValue(d), ms(d), label(d))
                }
                "dayoff" -> {
                    var d = today.plusDays(1)
                    while (!(d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in holidayDates)) d = d.plusDays(1)
                    list += Target("次の休日", ms(d), label(d) + (holidayDates[d]?.let { " $it" } ?: ""))
                }
                "fullmoon" -> list += Target("満月", moon.nextFull, null)
                "newmoon" -> list += Target("新月", moon.nextNew, null)
            }
        }
        config.custom.forEach { ev ->
            val p = parseDate(ev.date) ?: return@forEach
            val time = p.time ?: LocalTime.MIDNIGHT
            val at = when {
                p.date != null -> ms(p.date, time)
                else -> {
                    val md = p.monthDay!!
                    var d = md.atYear(today.year)
                    if (ms(d, time) <= now) d = md.atYear(today.year + 1)
                    ms(d, time)
                }
            }
            if (at > now) list += Target(ev.name, at, Instant.ofEpochMilli(at).atZone(zone).toLocalDate().let(::label))
        }
        return list.filter { it.at > now }.sortedBy { it.at }
    }

    private fun label(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日（${"月火水木金土日"[d.dayOfWeek.value - 1]}）"
}
