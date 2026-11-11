package app.dashboard.data

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 端末の中に残してある設定の控え 1 つ（Web・アプリの設定の「バックアップ」に出す）。 */
@Serializable
data class BackupInfo(
    val name: String,
    val createdAt: Long,
    val size: Long,
    /** "auto"（1 日 1 回の自動）/ "manual"（「いま控える」）/ "prerestore"（戻す直前のいまの設定）。 */
    val kind: String,
)

/**
 * 設定（config.json）の控えを `filesDir/backups/` に世代つきで残す。
 *
 * - 自動: 前の自動の控えから 24 時間たったら 1 つ作る。直前の控えと中身が同じなら作らない。
 *   既定のままの設定（読めなくて起動した直後など）は自動では控えない（良い控えを押し出さないため）。
 * - 手動と「戻す直前」は、自動とは別の枠で残す。
 * - 控えにはトークン・PIN のハッシュが入る。端末の中だけに置き、外へは出さない（書き出しは /api/backup が別にある）。
 *
 * アプリを消すと filesDir ごと消える。この控えは「設定を間違えた」「壊れた」ときのためのもので、入れ直しの備えではない。
 */
class BackupStore(
    private val dir: File,
    private val json: Json,
    private val current: () -> Config,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    /** 自動の控えを作るかどうかの確認（サービスが 15 秒ごとに呼ぶ）。作ったら true。 */
    fun autoIfDue(): Boolean = synchronized(lock) {
        val now = clock()
        val last = list().firstOrNull { it.kind == AUTO }
        if (last != null && now - last.createdAt < AUTO_INTERVAL_MS) return false
        // 中身が変わっていなくて作らなかったときに、15 秒ごとに読み直さない
        if (checkedAt != 0L && now - checkedAt < RECHECK_MS) return false
        checkedAt = now
        val config = current()
        if (fingerprint(config) == fingerprint(Config())) return false
        val newest = list().firstOrNull()
        if (newest != null && read(newest.name)?.let { fingerprintOf(it) } == fingerprint(config)) return false
        write(AUTO, config) != null
    }

    private var checkedAt = 0L

    /** いまの設定を控える（[MANUAL] か [PRE_RESTORE]）。失敗したら null。 */
    fun create(kind: String): BackupInfo? = synchronized(lock) { write(kind, current()) }

    /** 新しい順。 */
    fun list(): List<BackupInfo> = synchronized(lock) {
        val files = dir.listFiles { f -> f.isFile && NAME.matches(f.name) } ?: return emptyList()
        files.mapNotNull { f ->
            val m = NAME.matchEntire(f.name) ?: return@mapNotNull null
            val time = runCatching { stamp().parse(m.groupValues[1])?.time }.getOrNull() ?: return@mapNotNull null
            BackupInfo(f.name, time, f.length(), m.groupValues[2])
        }.sortedWith(compareByDescending<BackupInfo> { it.createdAt }.thenByDescending { it.name })
    }

    /** 控えの中身。名前は [list] の中のものに限る（ほかのファイルは読まない）。 */
    fun read(name: String): String? = synchronized(lock) {
        if (!NAME.matches(name)) return null
        File(dir, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    }

    private fun write(kind: String, config: Config): BackupInfo? {
        return try {
            dir.mkdirs()
            val base = "config-${stamp().format(Date(clock()))}-$kind"
            var file = File(dir, "$base.json")
            var n = 1
            while (file.exists()) file = File(dir, "$base-${n++}.json")
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(config))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            prune()
            list().firstOrNull { it.name == file.name }
        } catch (e: Exception) {
            Log.w(TAG, "設定の控えの保存に失敗", e)
            null
        }
    }

    /** 自動は [KEEP_AUTO] 個、手動と戻す直前は合わせて [KEEP_OTHER] 個まで。古いほうから消す。 */
    private fun prune() {
        val all = list()
        all.filter { it.kind == AUTO }.drop(KEEP_AUTO).forEach { File(dir, it.name).delete() }
        all.filter { it.kind != AUTO }.drop(KEEP_OTHER).forEach { File(dir, it.name).delete() }
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    // configVersion は保存のたびに変わるので、中身の比べには含めない
    private fun fingerprint(c: Config): String = json.encodeToString(c.copy(configVersion = 0))

    private fun fingerprintOf(text: String): String? =
        runCatching { fingerprint(json.decodeFromString<Config>(text)) }.getOrNull()

    companion object {
        private const val TAG = "BackupStore"
        const val AUTO = "auto"
        const val MANUAL = "manual"
        const val PRE_RESTORE = "prerestore"
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val RECHECK_MS = 60L * 60 * 1000
        const val KEEP_AUTO = 7
        const val KEEP_OTHER = 5

        private val NAME = Regex("config-(\\d{8}-\\d{6})-(auto|manual|prerestore)(?:-\\d+)?\\.json")

        private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
