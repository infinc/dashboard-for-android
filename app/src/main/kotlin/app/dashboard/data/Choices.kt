package app.dashboard.data

/** アクセント色の候補。アプリと Web の設定画面はどちらもこの一覧から選ばせる。 */
object Accents {
    data class Accent(val hex: String, val label: String)

    const val DEFAULT = "#4DD4FF"

    val ALL = listOf(
        Accent("#4DD4FF", "シアン"),
        Accent("#5AA9FF", "スカイ"),
        Accent("#6B8CFF", "ブルー"),
        Accent("#8B7CFF", "バイオレット"),
        Accent("#B57CFF", "パープル"),
        Accent("#FF7EB6", "ピンク"),
        Accent("#FF8A7A", "コーラル"),
        Accent("#FF9F43", "オレンジ"),
        Accent("#FFB347", "アンバー"),
        Accent("#FFD166", "イエロー"),
        Accent("#B8E05C", "ライム"),
        Accent("#3DDC97", "グリーン"),
        Accent("#2EC4B6", "ティール"),
        Accent("#C7D2E0", "シルバー"),
    )

    fun isKnown(hex: String): Boolean = ALL.any { it.hex.equals(hex, ignoreCase = true) }
}

/**
 * カードの背景色の候補。カード全体で 1 色。色そのものではなく、テーマの面の色に混ぜて使う
 * （ダークでもホワイトでも文字が読めるように。混ぜる割合は ui/theme の [app.dashboard.ui.theme.cardSurface]）。
 * 空文字は「既定」（テーマの面の色のまま）。
 */
object CardColors {
    data class CardColor(val hex: String, val label: String)

    val ALL = listOf(
        CardColor("", "既定"),
        CardColor("#3B6FB6", "ネイビー"),
        CardColor("#2F9E8F", "ティール"),
        CardColor("#3E9B4F", "グリーン"),
        CardColor("#7A5BD0", "パープル"),
        CardColor("#C0508A", "ローズ"),
        CardColor("#C0563E", "レッド"),
        CardColor("#B8862F", "ブラウン"),
        CardColor("#6B7A8F", "スレート"),
        CardColor("#000000", "ブラック"),
        CardColor("#FFFFFF", "ホワイト"),
    )

    fun isKnown(hex: String): Boolean = ALL.any { it.hex.equals(hex, ignoreCase = true) }
}

/**
 * 通知音の候補。[Note] の並びをその場で合成して鳴らす。
 * 充電の抜き差しでは同じ音色を、挿したときはそのまま・抜いたときは音の高さを逆順にして鳴らす。
 */
object Tones {
    enum class Wave { SINE, TRIANGLE, SQUARE }

    /** [start] と [duration] は秒。 */
    data class Note(val frequency: Double, val start: Double, val duration: Double)

    data class Tone(val id: String, val label: String, val wave: Wave, val notes: List<Note>)

    const val DEFAULT_DISASTER = "chime"
    const val DEFAULT_CHARGING = "rise"
    const val DEFAULT_TIMER = "beep"
    const val DEFAULT_BATTERY_LOW = "descend"
    const val DEFAULT_MEMO = "notice"
    const val DEFAULT_BATTERY_HOT = "alarm"
    const val DEFAULT_WIFI_LOST = "knock"
    const val DEFAULT_RAIN = "soft"

    val ALL = listOf(
        Tone("chime", "チャイム", Wave.TRIANGLE, listOf(Note(1318.5, 0.0, 0.20), Note(987.8, 0.17, 0.36))),
        Tone("beep", "ビープ", Wave.SINE, listOf(Note(880.0, 0.0, 0.32), Note(880.0, 0.45, 0.32), Note(880.0, 0.9, 0.32))),
        Tone("rise", "ド・ソ", Wave.SINE, listOf(Note(523.3, 0.0, 0.14), Note(784.0, 0.13, 0.26))),
        Tone("bell", "ベル", Wave.SINE, listOf(Note(1046.5, 0.0, 0.6), Note(1318.5, 0.22, 0.6), Note(1568.0, 0.44, 0.8))),
        Tone("marimba", "木琴", Wave.TRIANGLE, listOf(Note(784.0, 0.0, 0.14), Note(988.0, 0.12, 0.14), Note(1175.0, 0.24, 0.22))),
        Tone("alarm", "アラーム", Wave.SQUARE, listOf(Note(1760.0, 0.0, 0.09), Note(1760.0, 0.16, 0.09), Note(1760.0, 0.32, 0.09), Note(1760.0, 0.48, 0.09))),
        Tone("soft", "やわらか", Wave.SINE, listOf(Note(440.0, 0.0, 0.4), Note(554.4, 0.32, 0.5))),
        Tone("ping", "ピン", Wave.SINE, listOf(Note(1568.0, 0.0, 0.25))),
        Tone("doorbell", "ピンポン", Wave.TRIANGLE, listOf(Note(659.3, 0.0, 0.5), Note(523.3, 0.45, 0.8))),
        Tone("school", "学校のチャイム", Wave.SINE, listOf(Note(659.3, 0.0, 0.55), Note(523.3, 0.45, 0.55), Note(587.3, 0.9, 0.55), Note(392.0, 1.35, 0.9))),
        Tone("arpeggio", "和音", Wave.SINE, listOf(Note(523.3, 0.0, 0.25), Note(659.3, 0.09, 0.25), Note(784.0, 0.18, 0.25), Note(1046.5, 0.27, 0.45))),
        Tone("harp", "ハープ", Wave.TRIANGLE, listOf(Note(523.3, 0.0, 0.35), Note(587.3, 0.07, 0.35), Note(659.3, 0.14, 0.35), Note(784.0, 0.21, 0.35), Note(880.0, 0.28, 0.35), Note(1046.5, 0.35, 0.5))),
        Tone("notice", "お知らせ", Wave.SINE, listOf(Note(880.0, 0.0, 0.12), Note(1174.7, 0.14, 0.12), Note(1760.0, 0.28, 0.3))),
        Tone("descend", "下る音", Wave.TRIANGLE, listOf(Note(1046.5, 0.0, 0.16), Note(784.0, 0.12, 0.16), Note(523.3, 0.24, 0.3))),
        Tone("crystal", "きらきら", Wave.SINE, listOf(Note(2093.0, 0.0, 0.5), Note(2637.0, 0.06, 0.5), Note(3136.0, 0.12, 0.6))),
        Tone("bird", "小鳥", Wave.SINE, listOf(Note(2637.0, 0.0, 0.06), Note(3136.0, 0.08, 0.06), Note(2637.0, 0.16, 0.06), Note(3520.0, 0.24, 0.1))),
        Tone("pop", "ポップ", Wave.SINE, listOf(Note(660.0, 0.0, 0.06), Note(990.0, 0.05, 0.1))),
        Tone("coin", "コイン", Wave.SQUARE, listOf(Note(988.0, 0.0, 0.08), Note(1318.5, 0.08, 0.35))),
        Tone("fanfare", "ファンファーレ", Wave.SQUARE, listOf(Note(392.0, 0.0, 0.14), Note(523.3, 0.15, 0.14), Note(659.3, 0.3, 0.14), Note(784.0, 0.45, 0.5))),
        Tone("gong", "ゴーン", Wave.SINE, listOf(Note(196.0, 0.0, 1.6), Note(293.7, 0.0, 1.4))),
        Tone("knock", "ノック", Wave.TRIANGLE, listOf(Note(220.0, 0.0, 0.06), Note(220.0, 0.16, 0.06))),
        Tone("siren", "サイレン", Wave.SQUARE, listOf(Note(960.0, 0.0, 0.24), Note(770.0, 0.25, 0.24), Note(960.0, 0.5, 0.24), Note(770.0, 0.75, 0.24))),
    )

    fun byId(id: String?, fallback: String): Tone =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == fallback }

    fun isKnown(id: String): Boolean = ALL.any { it.id == id }
}
