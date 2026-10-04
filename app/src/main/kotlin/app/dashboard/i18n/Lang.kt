package app.dashboard.i18n

import androidx.compose.runtime.mutableStateOf
import java.util.Locale

/**
 * 表示の言語（日本語 / 英語）。設定の [app.dashboard.data.DisplayConfig.language] を [ConfigStore] が読み込み・保存のたびに入れる。
 *
 * 文字列は Android の strings.xml ではなく、使う場所に日本語と英語を並べて書く（[L]）。
 * 画面だけでなく、取得先のエラーや通知・Web の設定画面へ返す選択肢の名前など、Context の無い所でも同じように切り替えるため。
 * 値は Compose の状態なので、画面で [L] を呼んだ所は言語を変えたときに描き直される。
 */
object Lang {
    const val JA = "ja"
    const val EN = "en"
    val ALL = listOf(JA, EN)

    private val state = mutableStateOf(JA)

    var current: String
        get() = state.value
        set(value) {
            val v = if (value == EN) EN else JA
            if (state.value != v) state.value = v
        }

    val en: Boolean get() = state.value == EN

    /** 日付や数の書き方に使う Locale。 */
    val locale: Locale get() = if (en) Locale.US else Locale.JAPAN
}

/** 日本語と英語のうち、いまの言語のほうを返す。 */
fun L(ja: String, en: String): String = if (Lang.en) en else ja

/** 項目の区切り（日本語は「 ・ 」、英語は「 · 」）。 */
val SEP: String get() = L(" ・ ", " · ")
