package app.dashboard.data

/**
 * GitHub のリリースの説明文（Markdown）を、画面に並べる行にする。
 *
 * リリースの説明文は次の形で書かれている:
 * ```
 * # Dashboard_v1.2.5        ← 見出し 1（リリース名。版は別に出すので捨てる）
 * - **概要**                ← 太字だけの箇条 = 節の見出し
 *   更なる拡張性の向上       ← 節の中の文
 * - **新機能**
 *   - Spotify の背景を…      ← 箇条（字下げ 2 つで 1 段）
 *     - 単色…               ← 2 段目
 * ```
 * 中身の無い節（「- **改善点**」だけ）は出さない。`**` と `` ` `` は外す。
 */
object ReleaseNotes {

    /** [heading] は節の見出し。[depth] は箇条の段（0 = 節の中の文、1 = 1 段目の箇条）。 */
    data class Line(val text: String, val depth: Int = 0, val bullet: Boolean = false, val heading: Boolean = false)

    fun parse(body: String?): List<Line> {
        if (body.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<Line>()
        for (raw in body.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("# ")) continue
            val indent = raw.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 2L else 1L }.toInt() / 2
            when {
                trimmed.startsWith("#") -> lines += Line(clean(trimmed.trimStart('#')), heading = true)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val text = trimmed.substring(2).trim()
                    // 字下げの無い太字だけの箇条は節の見出し
                    if (indent == 0 && text.length > 4 && text.startsWith("**") && text.endsWith("**")) {
                        lines += Line(clean(text), heading = true)
                    } else {
                        lines += Line(clean(text), depth = maxOf(indent, 1), bullet = true)
                    }
                }
                else -> lines += Line(clean(trimmed), depth = 0)
            }
        }
        // 中身の無い節の見出しを落とす
        return lines.filterIndexed { i, line -> !line.heading || lines.getOrNull(i + 1)?.heading == false }
            .filter { it.text.isNotEmpty() }
    }

    private fun clean(text: String): String = text.replace("**", "").replace("`", "").trim()
}
