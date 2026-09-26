package app.walldash.data

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

    val ALL = listOf(
        Tone("chime", "チャイム", Wave.TRIANGLE, listOf(Note(1318.5, 0.0, 0.20), Note(987.8, 0.17, 0.36))),
        Tone("beep", "ビープ", Wave.SINE, listOf(Note(880.0, 0.0, 0.32), Note(880.0, 0.45, 0.32), Note(880.0, 0.9, 0.32))),
        Tone("rise", "ド・ソ", Wave.SINE, listOf(Note(523.3, 0.0, 0.14), Note(784.0, 0.13, 0.26))),
        Tone("bell", "ベル", Wave.SINE, listOf(Note(1046.5, 0.0, 0.6), Note(1318.5, 0.22, 0.6), Note(1568.0, 0.44, 0.8))),
        Tone("marimba", "木琴", Wave.TRIANGLE, listOf(Note(784.0, 0.0, 0.14), Note(988.0, 0.12, 0.14), Note(1175.0, 0.24, 0.22))),
        Tone("alarm", "アラーム", Wave.SQUARE, listOf(Note(1760.0, 0.0, 0.09), Note(1760.0, 0.16, 0.09), Note(1760.0, 0.32, 0.09), Note(1760.0, 0.48, 0.09))),
        Tone("soft", "やわらか", Wave.SINE, listOf(Note(440.0, 0.0, 0.4), Note(554.4, 0.32, 0.5))),
        Tone("ping", "ピン", Wave.SINE, listOf(Note(1568.0, 0.0, 0.25))),
    )

    fun byId(id: String?, fallback: String): Tone =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == fallback }

    fun isKnown(id: String): Boolean = ALL.any { it.id == id }
}
