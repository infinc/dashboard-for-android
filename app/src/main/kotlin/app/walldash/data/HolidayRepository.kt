package app.walldash.data

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import java.io.File
import java.nio.charset.Charset
import java.time.LocalDate

/**
 * 国民の祝日。内閣府が公開している CSV（翌年の分まで載っている）を週に 1 回だけ取り、filesDir/holidays.csv に置く。
 * カウントダウンの「次の祝日」「次の休日」にだけ使う。
 */
class HolidayRepository(context: Context, private val client: HttpClient) {

    private val cacheFile = File(context.filesDir, "holidays.csv")

    @Volatile
    private var all: List<Holiday> = runCatching { if (cacheFile.exists()) parse(cacheFile.readText()) else emptyList() }.getOrDefault(emptyList())

    private var lastAttemptAt = 0L

    /** 今日以降の祝日（古い順）。 */
    fun upcoming(): List<Holiday> {
        val today = LocalDate.now().toString()
        return all.filter { it.date >= today }
    }

    suspend fun refreshIfDue(wanted: Boolean) {
        if (!wanted) return
        val now = System.currentTimeMillis()
        val fresh = cacheFile.exists() && now - cacheFile.lastModified() < CACHE_MS
        // 失敗しても 1 時間は取り直さない（毎回の見回りで叩き続けない）
        if (fresh && all.isNotEmpty() || now - lastAttemptAt < RETRY_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        try {
            val text = String(client.get(URL).readRawBytes(), Charset.forName("Shift_JIS"))
            val parsed = parse(text)
            if (parsed.isEmpty()) error("祝日の CSV を読めませんでした")
            all = parsed
            runCatching { cacheFile.writeText(text) }
        } catch (e: Exception) {
            Log.w(TAG, "祝日の取得に失敗", e)
        }
    }

    /** 1 行目は見出し。"2026/1/1,元日" の形。 */
    private fun parse(text: String): List<Holiday> = text.lineSequence().drop(1).mapNotNull { line ->
        val cols = line.trim().split(',')
        if (cols.size < 2) return@mapNotNull null
        val d = cols[0].split('/').mapNotNull { it.toIntOrNull() }
        if (d.size != 3) return@mapNotNull null
        runCatching { Holiday(LocalDate.of(d[0], d[1], d[2]).toString(), cols[1].trim()) }.getOrNull()
    }.toList()

    private companion object {
        const val TAG = "HolidayRepository"
        const val URL = "https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv"
        const val CACHE_MS = 7L * 24 * 3600 * 1000
        const val RETRY_MS = 3600_000L
    }
}
