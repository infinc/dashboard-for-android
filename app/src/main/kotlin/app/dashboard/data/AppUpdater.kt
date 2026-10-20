package app.dashboard.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.dashboard.i18n.L
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest

/** GitHub の Releases にある新しい APK。 */
data class ReleaseInfo(
    val tag: String,
    /** 表示用（"v1.2.5"）。 */
    val label: String,
    val apkUrl: String,
    val size: Long,
    /** `sha256:…` の 16 進（GitHub の API が返したときだけ）。 */
    val sha256: String?,
    /** リリースの説明文（Markdown のまま。画面では `ReleaseNotes.parse()` を通す）。 */
    val notes: String = "",
)

/** 更新のあとに初めて起動したときに出す「何が変わったか」。 */
data class WhatsNew(val label: String, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val release: ReleaseInfo?) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    /** [done] / [total] はバイト数（合計が分からなければ 0）。 */
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateState
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
    /** [release] は分かっている新しい版（ダウンロードの失敗や、見つけたあとの確認の失敗）。 */
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateState

    /** 新しい版が見つかっている（ダウンロード中・ダウンロード済み・その失敗も含む）。 */
    val hasUpdate: Boolean
        get() = this is Available || this is Downloading || this is Ready || (this is Failed && release != null)
}

/**
 * タブレットだけでアプリを更新する。
 *
 * GitHub の最新リリース（`/releases/latest`）の APK を端末にダウンロードして、システムのインストーラーを開く。
 * インストールの確認はシステムの画面で行われ、アプリが勝手に入れ替えることはない。
 * 署名が違う APK（PC でビルドしたもの ↔ Releases のもの）は上書きできず、インストーラーが失敗を知らせる。
 */
class AppUpdater(
    private val context: Context,
    private val client: HttpClient,
    private val currentLabel: String,
    private val repo: String = REPO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** 画面中央の案内（起動時・フッターのボタン）。ダッシュボードが描く。 */
    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen: StateFlow<Boolean> = _dialogOpen

    fun showDialog() { _dialogOpen.value = true }
    fun dismiss() { _dialogOpen.value = false }

    val hasUpdate: Boolean get() = _state.value.hasUpdate

    /** 更新のあとに初めて起動したときのリリースノート（閉じたら null）。 */
    private val _whatsNew = MutableStateFlow<WhatsNew?>(null)
    val whatsNew: StateFlow<WhatsNew?> = _whatsNew

    fun dismissWhatsNew() { _whatsNew.value = null }

    private val prefs by lazy { context.getSharedPreferences("updater", Context.MODE_PRIVATE) }

    /**
     * 前に起動した版（`lastRunVersion`）より新しい版で起動したら、その版のリリースノートを出す。
     * ノートは、アプリ内の更新でダウンロードしたときに控えたもの（`pendingTag` / `pendingNotes`）を使い、
     * 無ければ（ブラウザや PC から入れたとき）GitHub から取る。
     * この仕組みの無い版からの更新は `lastRunVersion` が無いので、インストールし直された跡（`lastUpdateTime`）で見分ける。
     */
    suspend fun checkJustUpdated() {
        val last = prefs.getString(KEY_LAST_RUN, null)
        prefs.edit().putString(KEY_LAST_RUN, currentLabel).apply()
        val updated = if (last != null) isNewer(currentLabel, last) else wasReinstalled()
        val pendingTag = prefs.getString(KEY_PENDING_TAG, null)
        val pendingNotes = prefs.getString(KEY_PENDING_NOTES, null)
        // ダウンロードしたまま入れていない新しい版のノートは残す（あとでインストールしたときに出す）
        if (pendingTag == null || !isNewer(pendingTag, currentLabel)) {
            prefs.edit().remove(KEY_PENDING_TAG).remove(KEY_PENDING_NOTES).apply()
        }
        // 前の起動でダウンロードした APK（入れ替えたあとの古いものを含む）はもう使わない。キャッシュに 20 MB ほど残り続けないよう消す
        // （この起動でダウンロード中・ダウンロード済みのものは消さない）
        if (lock.tryLock()) {
            try {
                if (_state.value !is UpdateState.Ready) withContext(Dispatchers.IO) { dir.deleteRecursively() }
            } finally {
                lock.unlock()
            }
        }
        if (!updated) return
        val label = versionOf(currentLabel)?.let { "v${it[0]}.${it[1]}.${it[2]}" } ?: currentLabel
        val notes = if (pendingTag != null && versionOf(pendingTag) == versionOf(currentLabel)) {
            pendingNotes.orEmpty()
        } else {
            runCatching { fetchNotesFor(currentLabel) }.getOrNull().orEmpty()
        }
        // 前の版が分からない（この仕組みの無い版から・入れ直し）ときは、公開されたリリースの版だけ出す
        // （PC で版を下げて入れ直したときや、まだ公開していない版で出さないように）
        if (last == null && notes.isBlank()) return
        _whatsNew.value = WhatsNew(label, notes)
    }

    private fun wasReinstalled(): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    /** いまの版と同じ x.y.z のタグのリリースの説明文。 */
    private suspend fun fetchNotesFor(label: String): String? {
        val want = versionOf(label) ?: return null
        val text = client.get("https://api.github.com/repos/$repo/releases?per_page=30") {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Dashboard-Android")
        }.bodyAsText()
        return Http.json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
            .firstOrNull { r -> r["tag_name"]?.jsonPrimitive?.contentOrNull?.let(::versionOf) == want }
            ?.get("body")?.jsonPrimitive?.contentOrNull
    }

    private val lock = Mutex()
    private val dir get() = File(context.cacheDir, "update")

    suspend fun check() {
        if (!lock.tryLock()) return
        try {
            // ダウンロード済みの APK があるなら、確認し直して状態を消さない
            val before = _state.value
            // APK が残っていれば Ready のまま。消されていたら（キャッシュの掃除など）確認し直す
            if (before is UpdateState.Ready && before.file.exists()) return
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchLatest()
                if (isNewer(release.tag, currentLabel)) UpdateState.Available(release) else UpdateState.UpToDate(release)
            } catch (e: CancellationException) {
                // 確認の途中で設定画面を閉じただけ。失敗として 12 時間出し続けない
                _state.value = before
                throw e
            } catch (e: Exception) {
                // 前に見つけた新しい版は覚えておく（フッターのボタンを消さない）
                val known = (before as? UpdateState.Available)?.release ?: (before as? UpdateState.Failed)?.release
                UpdateState.Failed(L("確認できませんでした: ${reason(e)}", "Couldn't check: ${reason(e)}"), known)
            }
        } finally {
            lock.unlock()
        }
    }

    /** APK をダウンロードして、できたらシステムのインストーラーを開く。 */
    suspend fun downloadAndInstall() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Ready -> { install(s.file); return }
            is UpdateState.Failed -> s.release
            else -> return
        } ?: run {
            // 確認の失敗のあとの「再試行」: 確認し直し、新しい版があればそのままダウンロードへ
            check()
            (_state.value as? UpdateState.Available)?.release ?: return
        }
        if (!lock.tryLock()) return
        try {
            _state.value = UpdateState.Downloading(release, 0, release.size)
            val file = try {
                download(release)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                File(dir, "dashboard.apk.part").delete()
                throw e
            } catch (e: Exception) {
                // 書きかけ（約 20 MB）を次の起動まで残さない
                File(dir, "dashboard.apk.part").delete()
                _state.value = UpdateState.Failed(L("ダウンロードできませんでした: ${reason(e)}", "Download failed: ${reason(e)}"), release)
                return
            }
            _state.value = UpdateState.Ready(release, file)
            // 入れ替わったあとの最初の起動で出すリリースノート（通信せずに済むように控える）
            prefs.edit().putString(KEY_PENDING_TAG, release.tag).putString(KEY_PENDING_NOTES, release.notes).apply()
            install(file)
        } finally {
            lock.unlock()
        }
    }

    /** 「インストール」を押し直す（提供元の許可を出したあとなど）。 */
    fun install(file: File) {
        try {
            openInstaller(file)
        } catch (e: Exception) {
            // インストーラーが無い端末など。ダウンロードした APK は残す（状態は Ready のまま）
            android.util.Log.w("AppUpdater", "install", e)
        }
    }

    private fun openInstaller(file: File) {
        val ctx = context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            // このアプリからのインストールを許可する画面。許可して戻ったら、もう一度押してもらう
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 許可が出ているか（出ていなければ、押すと許可の画面へ行く）。 */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private suspend fun fetchLatest(): ReleaseInfo {
        val text = client.get("https://api.github.com/repos/$repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
            header("User-Agent", "Dashboard-Android")
        }.bodyAsText()
        return parseRelease(text) ?: error(L("APK が見つかりません", "No APK in the release"))
    }

    private suspend fun download(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val part = File(dir, "dashboard.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        client.prepareGet(release.apkUrl) {
            header("User-Agent", "Dashboard-Android")
            timeout {
                requestTimeoutMillis = 15 * 60_000L
                socketTimeoutMillis = 60_000L
            }
        }.execute { response ->
            val total = response.headers["Content-Length"]?.toLongOrNull() ?: release.size
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var lastReport = 0L
            part.outputStream().buffered().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    // 画面の描き直しを減らすため、約 1% ごと
                    if (done - lastReport >= maxOf(total / 100, 64 * 1024L)) {
                        lastReport = done
                        _state.value = UpdateState.Downloading(release, done, total)
                    }
                }
            }
            if (release.size > 0 && done != release.size) {
                error(L("途中で切れました（$done / ${release.size} バイト）", "Download was cut off ($done / ${release.size} bytes)"))
            }
        }
        if (release.sha256 != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                part.delete()
                error(L("ファイルの SHA-256 が一致しません", "The file's SHA-256 doesn't match"))
            }
        }
        val apk = File(dir, "dashboard.apk")
        check(part.renameTo(apk)) { "rename" }
        apk
    }

    private fun reason(e: Exception) = e.message ?: e.javaClass.simpleName

    companion object {
        const val REPO = "infinc/dashboard-for-android"
        private const val KEY_LAST_RUN = "lastRunVersion"
        private const val KEY_PENDING_TAG = "pendingTag"
        private const val KEY_PENDING_NOTES = "pendingNotes"

        /** `v1.2.5` / `dashboard_v1.2.5` / `1.2.5-20261010` → [1, 2, 5]。読めなければ null。 */
        fun versionOf(text: String): List<Int>? =
            Regex("""(\d+)\.(\d+)\.(\d+)""").find(text)?.destructured?.toList()?.map(String::toInt)

        fun isNewer(tag: String, current: String): Boolean {
            val a = versionOf(tag) ?: return false
            val b = versionOf(current) ?: return true
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }

        /** `/releases/latest` の JSON から、APK（`dashboard.apk` を優先）の情報を取り出す。 */
        fun parseRelease(json: String): ReleaseInfo? {
            val root = Http.json.parseToJsonElement(json).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val assets = root["assets"]?.jsonArray?.map { it.jsonObject } ?: return null
            val apks = assets.filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
            val asset = apks.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "dashboard.apk" } ?: apks.firstOrNull() ?: return null
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            // 配布元は GitHub だけ（API の返事に別のホストが紛れていても使わない）
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            if (!url.startsWith("https://") || !(host == "github.com" || host.endsWith(".github.com"))) return null
            val sha = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:")?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }
            val label = versionOf(tag)?.let { "v${it[0]}.${it[1]}.${it[2]}" } ?: tag
            val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return ReleaseInfo(tag, label, url, asset["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L, sha, notes)
        }
    }
}
