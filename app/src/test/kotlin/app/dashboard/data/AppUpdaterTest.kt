package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    private val sha = "1fb29a33e64dc42c3742d513eeddc01c1894e0deba72e2b45b6ba2e536c633ba"

    private fun release(url: String = "https://github.com/infinc/dashboard-for-android/releases/download/dashboard_v1.2.6/dashboard.apk", name: String = "dashboard.apk") =
        """
        {"tag_name":"dashboard_v1.2.6","body":"# Dashboard_v1.2.6\r\n- **新機能**","assets":[
          {"name":"notes.txt","size":10,"browser_download_url":"https://github.com/x/notes.txt"},
          {"name":"$name","size":19541956,"digest":"sha256:$sha","browser_download_url":"$url"}
        ]}
        """.trimIndent()

    @Test
    fun `版は数として比べる`() {
        assertTrue(AppUpdater.isNewer("dashboard_v1.2.10", "v1.2.5"))
        assertTrue(AppUpdater.isNewer("dashboard_v2.0.0", "v1.9.9-20261010"))
        assertFalse(AppUpdater.isNewer("dashboard_v1.2.5", "v1.2.5"))
        assertFalse(AppUpdater.isNewer("dashboard_v1.2.4", "v1.2.5"))
    }

    @Test
    fun `読めないタグは新しいとみなさない`() {
        assertFalse(AppUpdater.isNewer("nightly", "v1.2.5"))
        assertNull(AppUpdater.versionOf("nightly"))
    }

    @Test
    fun `リリースから APK を取り出す`() {
        val r = AppUpdater.parseRelease(release())!!
        assertEquals("dashboard_v1.2.6", r.tag)
        assertEquals("v1.2.6", r.label)
        assertEquals(19541956L, r.size)
        assertEquals(sha, r.sha256)
        assertTrue(r.apkUrl.endsWith("/dashboard.apk"))
        assertEquals("# Dashboard_v1.2.6\r\n- **新機能**", r.notes)
    }

    @Test
    fun `GitHub 以外や平文の URL は使わない`() {
        assertNull(AppUpdater.parseRelease(release(url = "https://example.com/dashboard.apk")))
        assertNull(AppUpdater.parseRelease(release(url = "http://github.com/x/dashboard.apk")))
        assertNull(AppUpdater.parseRelease(release(url = "https://github.com.example.com/dashboard.apk")))
    }

    @Test
    fun `APK が無ければ null`() {
        assertNull(AppUpdater.parseRelease(release(name = "dashboard.zip")))
    }
}
