package app.dashboard

import app.dashboard.data.BrowserConfig
import app.dashboard.data.Config
import app.dashboard.data.LanConfig
import app.dashboard.data.MAX_PRESETS
import app.dashboard.data.Preset
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
        if (c.presets.items.isNotEmpty()) c.presets else PresetsConfig(FIRST_ID, listOf(Preset(FIRST_ID, "A")))

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
            c.copy(presets = PresetsConfig(id, items))
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
        graph.config.update { c -> c.copy(presets = c.presets.copy(items = c.presets.items.filterNot { it.id == id })) }
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
                presets = PresetsConfig(id, items),
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

    /** 切り替え先の控え [base] に、切り替えても変わらないものを [current] から戻す。 */
    private fun keep(base: Config, current: Config): Config = base.copy(
        configVersion = current.configVersion,
        browser = current.browser,
        lan = current.lan,
        settingsLock = current.settingsLock,
    )

    private fun newId(p: PresetsConfig): String =
        (1..MAX_PRESETS + 1).map { "p$it" }.first { id -> p.items.none { it.id == id } }

    private fun nextName(p: PresetsConfig): String =
        ('A'..'Z').map { it.toString() }.first { n -> p.items.none { it.name == n } }

    private fun cleanName(name: String) = name.trim().take(MAX_NAME)

    companion object {
        const val FIRST_ID = "p1"
        const val MAX_NAME = 20
    }
}
