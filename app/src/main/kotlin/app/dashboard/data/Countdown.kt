package app.dashboard.data

import app.dashboard.i18n.L
import app.dashboard.i18n.Lang
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
    val BUILTIN_LABELS get() = linkedMapOf(
        "newyear" to L("新年", "New Year"),
        "christmas" to L("クリスマス", "Christmas"),
        "holiday" to L("次の祝日", "Next holiday"),
        "dayoff" to L("次の休日（土日・祝日）", "Next day off (weekend or holiday)"),
        "fullmoon" to L("次の満月", "Next full moon"),
        "newmoon" to L("次の新月", "Next new moon"),
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
    /** 内閣府の祝日の名前（英語のときは英語の名前。知らない名前はそのまま）。 */
    fun holidayName(ja: String): String = if (!Lang.en) ja else HOLIDAYS_EN[ja] ?: ja

    private val HOLIDAYS_EN = mapOf(
        "元日" to "New Year's Day",
        "成人の日" to "Coming of Age Day",
        "建国記念の日" to "National Foundation Day",
        "天皇誕生日" to "Emperor's Birthday",
        "春分の日" to "Vernal Equinox Day",
        "昭和の日" to "Showa Day",
        "憲法記念日" to "Constitution Memorial Day",
        "みどりの日" to "Greenery Day",
        "こどもの日" to "Children's Day",
        "海の日" to "Marine Day",
        "山の日" to "Mountain Day",
        "敬老の日" to "Respect for the Aged Day",
        "秋分の日" to "Autumnal Equinox Day",
        "体育の日" to "Health and Sports Day",
        "スポーツの日" to "Sports Day",
        "文化の日" to "Culture Day",
        "勤労感謝の日" to "Labor Thanksgiving Day",
        "休日" to "Substitute holiday",
        "振替休日" to "Substitute holiday",
        "国民の休日" to "Citizens' holiday",
    )

    fun targets(config: CountdownConfig, holidays: List<Holiday>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Target> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun ms(date: LocalDate, time: LocalTime = LocalTime.MIDNIGHT) = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        val holidayDates = holidays.mapNotNull { h -> runCatching { LocalDate.parse(h.date) to holidayName(h.name) }.getOrNull() }.toMap()
        val moon by lazy { Astro.moon(now) }

        val list = mutableListOf<Target>()
        config.builtins.forEach { key ->
            when (key) {
                "newyear" -> list += Target(L("新年", "New Year"), ms(LocalDate.of(today.year + 1, 1, 1)), L("${today.year + 1}年 1月1日", "Jan 1, ${today.year + 1}"))
                "christmas" -> {
                    val thisYear = LocalDate.of(today.year, 12, 25)
                    val d = if (today.isAfter(thisYear)) thisYear.plusYears(1) else thisYear
                    list += Target(L("クリスマス", "Christmas"), ms(d), L("12月25日", "Dec 25"))
                }
                "holiday" -> holidayDates.keys.filter { it.isAfter(today) }.minOrNull()?.let { d ->
                    list += Target(holidayDates.getValue(d), ms(d), label(d))
                }
                "dayoff" -> {
                    var d = today.plusDays(1)
                    while (!(d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in holidayDates)) d = d.plusDays(1)
                    list += Target(L("次の休日", "Next day off"), ms(d), label(d) + (holidayDates[d]?.let { " $it" } ?: ""))
                }
                "fullmoon" -> list += Target(L("満月", "Full moon"), moon.nextFull, null)
                "newmoon" -> list += Target(L("新月", "New moon"), moon.nextNew, null)
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

    private fun label(d: LocalDate) = L("${d.monthValue}月${d.dayOfMonth}日（${"月火水木金土日"[d.dayOfWeek.value - 1]}）", "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)}, ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)} ${d.dayOfMonth}")
}
