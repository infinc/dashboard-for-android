package app.dashboard.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 設定の控え（自動は 1 日 1 回・中身が同じなら増やさない・世代の上限）。 */
class BackupStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    private var now = t0
    private var config = Config().copy(display = Config().display.copy(language = "en"))

    private fun store() = BackupStore(tmp.newFolder("b"), json, { config }, { now })

    @Test
    fun `auto backs up once a day`() {
        val s = store()
        assertTrue(s.autoIfDue())
        assertFalse(s.autoIfDue())
        now += day - 1
        config = config.copy(configVersion = 9, notifications = config.notifications.copy(timerTone = "changed"))
        assertFalse(s.autoIfDue())
        now += 2
        assertTrue(s.autoIfDue())
        assertEquals(2, s.list().size)
    }

    @Test
    fun `unchanged config is not backed up again`() {
        val s = store()
        assertTrue(s.autoIfDue())
        now += 3 * day
        config = config.copy(configVersion = config.configVersion + 5) // 保存の回数だけが変わった
        assertFalse(s.autoIfDue())
        assertEquals(1, s.list().size)
    }

    @Test
    fun `default config is never backed up automatically`() {
        config = Config()
        val s = store()
        assertFalse(s.autoIfDue())
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `old auto backups are pruned but manual ones have their own quota`() {
        val s = store()
        repeat(BackupStore.KEEP_AUTO + 3) { i ->
            config = config.copy(display = config.display.copy(language = if (i % 2 == 0) "ja" else "en"), notifications = config.notifications.copy(timerTone = "t$i"))
            assertTrue(s.autoIfDue())
            now += day + 1
        }
        repeat(BackupStore.KEEP_OTHER + 2) { assertNotNull(s.create(BackupStore.MANUAL)); now += 1000 }
        val all = s.list()
        assertEquals(BackupStore.KEEP_AUTO, all.count { it.kind == BackupStore.AUTO })
        assertEquals(BackupStore.KEEP_OTHER, all.count { it.kind == BackupStore.MANUAL })
        // 新しい順
        assertEquals(all.sortedByDescending { it.createdAt }, all)
    }

    @Test
    fun `same second backups do not overwrite each other`() {
        val s = store()
        val a = s.create(BackupStore.MANUAL)!!
        val b = s.create(BackupStore.MANUAL)!!
        assertTrue(a.name != b.name)
        assertEquals(2, s.list().size)
    }

    @Test
    fun `read only returns listed backup names`() {
        val s = store()
        val a = s.create(BackupStore.PRE_RESTORE)!!
        assertNotNull(s.read(a.name))
        assertNull(s.read("../config.json"))
        assertNull(s.read("config.json"))
        assertNull(s.read("config-20260101-000000-auto.json"))
    }
}
