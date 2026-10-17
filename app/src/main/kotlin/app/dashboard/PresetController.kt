package app.dashboard

import app.dashboard.data.BrowserConfig
import app.dashboard.data.Config
import app.dashboard.data.LanConfig
import app.dashboard.data.MAX_PRESETS
import app.dashboard.data.MAX_PRESET_TIMES
import app.dashboard.data.Preset
import app.dashboard.data.PresetTime
import app.dashboard.data.PresetsConfig
import app.dashboard.data.SettingsLockConfig
import app.dashboard.data.WallpaperConfig
import app.dashboard.i18n.L

/**
 * プリセット（設定のまとまり。最大 [MAX_PRESETS] 個）の作成・名前の変更・削除・切り替え。
 *
 * 使用中のプリセットの中身は、いまの設定そのもの。切り替えるときに、いまの設定を使用中のプリセットに控え、
 * 切り替え先の控えを設定に戻す。背景画像は filesDir/presets/{id}.jpg に控える（[app.dashboard.data.WallpaperStore.stash]）。
 * 切り替えても変わらないのは、ブラウズのお気に入り・LAN 公開と PIN・設定画面の PIN・プリセットの一覧そのもの（[keep]）。
 */
class PresetController(private val graph: AppGraph) {

    private val lock = Any()

    /** 一覧。まだ 1 つも作っていなければ、いまの設定を 1 つ目（名前は「A」）として返す（保存はしない）。 */
    fun list(c: Config = graph.config.get()): PresetsConfig =
        if (c.presets.items.isNotEmpty()) c.presets else c.presets.copy(active = FIRST_ID, items = listOf(Preset(FIRST_ID, "A")))

    /** いまの設定を写して新しいプリセットを作り、そこへ切り替える。 */
    fun create(name: String): Config = synchronized(lock) {
        val current = graph.config.get()
        val presets = list(current)
        if (presets.items.size >= MAX_PRESETS) {
            throw SettingsController.SettingsException("presets_full", L("プリセットは $MAX_PRESETS 個までです", "Up to $MAX_PRESETS presets"))
        }
        val id = newId(presets)
        val label = cleanName(name).ifEmpty { nextName(presets) }
        graph.wallpaper.stash(presets.active)
        graph.wallpaper.stash(id)
        graph.config.update { c ->
            val items = presets.items.map { if (it.id == presets.active) it.copy(config = strip(c)) else it } + Preset(id, label, strip(c))
            c.copy(presets = presets.copy(active = id, items = items))
        }
    }

    fun rename(id: String, name: String): Config = synchronized(lock) {
        val label = cleanName(name)
        if (label.isEmpty()) throw SettingsController.SettingsException("preset_name", L("名前を入れてください", "Enter a name"))
        val presets = list()
        graph.config.update { c -> c.copy(presets = presets.copy(items = presets.items.map { if (it.id == id) it.copy(name = label) else it })) }
    }

    /** 削除。最後の 1 つは消せない。使用中のものを消すときは、先に残りの最初のものへ切り替える。 */
    fun delete(id: String): Config = synchronized(lock) {
        val presets = list()
        if (presets.items.size <= 1) {
            throw SettingsController.SettingsException("preset_last", L("最後のプリセットは削除できません", "You can't delete the last preset"))
        }
        if (id == presets.active) switchLocked(presets.items.first { it.id != id }.id)
        graph.wallpaper.drop(id)
        graph.config.update { c ->
            c.copy(presets = c.presets.copy(items = c.presets.items.filterNot { it.id == id }, schedule = c.presets.schedule.filterNot { it.id == id }))
        }
    }

    // ------------------------------------------------------------ 時刻での切り替え

    /** 時刻での切り替えを保存する。時刻は "H:mm" も受け、"HH:mm" にそろえる。読めない時刻・無いプリセットは落とす。 */
    fun setSchedule(times: List<PresetTime>): Config = synchronized(lock) {
        val presets = list()
        val clean = times
            .mapNotNull { t -> parseTime(t.at)?.let { PresetTime(it.format(HHMM), t.id) } }
            .filter { t -> presets.items.any { it.id == t.id } }
            .distinctBy { it.at }
            .sortedBy { it.at }
            .take(MAX_PRESET_TIMES)
        graph.config.update { c -> c.copy(presets = presets.copy(schedule = clean)) }
    }

    private val prefs by lazy { graph.context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }

    /**
     * 常駐のサービスが 15 秒ごとに呼ぶ。前に見た時刻からいままでの間に切り替えの時刻があれば、その最後のものへ切り替える。
     * 端末が止まっていた間の分も、24 時間前までは拾う（夜中に再起動しても朝に「昼」へ戻る）。
     */
    fun tickSchedule(now: java.time.LocalDateTime = java.time.LocalDateTime.now()) {
        val schedule = graph.config.get().presets.schedule
        val nowMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val last = prefs.getLong(KEY_CHECKED, 0L)
        prefs.edit().putLong(KEY_CHECKED, nowMs).apply()
        if (schedule.isEmpty() || last == 0L) return
        val from = java.time.Instant.ofEpochMilli(maxOf(last, nowMs - DAY_MS)).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val due = due(schedule, from, now) ?: return
        if (list().active == due.id) return
        runCatching { switchTo(due.id) }
            .onFailure { android.util.Log.w("PresetController", "時刻での切り替えに失敗", it) }
    }

    /** [id] のプリセットへ切り替える。取得先が変わったものはすぐ取り直す。 */
    fun switchTo(id: String): Config = synchronized(lock) { switchLocked(id) }

    private fun switchLocked(id: String): Config {
        val before = graph.config.get()
        val presets = list(before)
        if (id == presets.active) return before
        val target = presets.items.firstOrNull { it.id == id }
            ?: throw SettingsController.SettingsException("preset_missing", L("プリセットが見つかりません", "Preset not found"))
        graph.wallpaper.stash(presets.active)
        val hasImage = graph.wallpaper.restore(id)
        val updated = graph.config.update { c ->
            val items = presets.items.map { if (it.id == presets.active) it.copy(config = strip(c)) else it }
            // 控えの無いプリセット（作った直後に壊れた等）は、いまの設定のまま名前だけ切り替える
            val base = target.config ?: strip(c)
            keep(base, c).copy(
                presets = presets.copy(active = id, items = items),
                wallpaper = WallpaperConfig(imageSetAt = if (hasImage) System.currentTimeMillis() else 0),
            )
        }
        if (graph.settings.sourcesChanged(before, updated)) graph.settings.refreshAllLater()
        return updated
    }

    /** プリセットに控える形（切り替えても変わらないものは空にする）。 */
    private fun strip(c: Config): Config = c.copy(
        browser = BrowserConfig(),
        lan = LanConfig(),
        settingsLock = SettingsLockConfig(),
        presets = PresetsConfig(),
    )

    /**
     * 切り替え先の控え [base] に、切り替えても変わらないものを [current] から戻す。
     * トークン・パスワード・API キーは、いまの値を引き継ぐ（控えの値は古い。Spotify の更新用トークンは使うたびに差し替わるので、
     * 控えに戻すと認可が切れる。片方で連携を解除したのに、もう片方に残るのも防ぐ）。
     * 同じアカウント・同じ送り先のときだけ引き継ぎ、別のアカウントを使うプリセットはその控えのままにする。
     */
    private fun keep(base: Config, current: Config): Config = base.copy(
        configVersion = current.configVersion,
        browser = current.browser,
        lan = current.lan,
        settingsLock = current.settingsLock,
        spotify = if (base.spotify.clientId == current.spotify.clientId) base.spotify.copy(refreshToken = current.spotify.refreshToken) else base.spotify,
        calendar = if (base.calendar.appleId == current.calendar.appleId) base.calendar.copy(password = current.calendar.password) else base.calendar,
        memo = if (base.memo.endpoint == current.memo.endpoint) base.memo.copy(token = current.memo.token) else base.memo,
        train = base.train.copy(token = current.train.token, challengeToken = current.train.challengeToken),
        ships = current.ships,
        notifyApi = current.notifyApi,
        github = if (base.github.user == current.github.user) base.github.copy(token = current.github.token) else base.github,
    )

    private fun newId(p: PresetsConfig): String =
        (1..MAX_PRESETS + 1).map { "p$it" }.first { id -> p.items.none { it.id == id } }

    private fun nextName(p: PresetsConfig): String =
        ('A'..'Z').map { it.toString() }.first { n -> p.items.none { it.name == n } }

    private fun cleanName(name: String) = name.trim().take(MAX_NAME)

    companion object {
        const val FIRST_ID = "p1"
        const val MAX_NAME = 20
        private const val PREFS = "dashboard_presets"
        private const val KEY_CHECKED = "scheduleCheckedAt"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private val HHMM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

        fun parseTime(text: String): java.time.LocalTime? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) java.time.LocalTime.of(h, min) else null
        }

        /** [from] より後、[to] まで（含む）に来た切り替えの時刻のうち、いちばん遅いもの。無ければ null。 */
        fun due(schedule: List<PresetTime>, from: java.time.LocalDateTime, to: java.time.LocalDateTime): PresetTime? {
            if (!to.isAfter(from)) return null
            var best: Pair<java.time.LocalDateTime, PresetTime>? = null
            var day = from.toLocalDate()
            while (!day.isAfter(to.toLocalDate())) {
                schedule.forEach { t ->
                    val at = parseTime(t.at)?.let { day.atTime(it) } ?: return@forEach
                    if (at.isAfter(from) && !at.isAfter(to) && (best == null || at.isAfter(best!!.first))) best = at to t
                }
                day = day.plusDays(1)
            }
            return best?.second
        }
    }
}
