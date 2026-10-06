package app.dashboard.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLabelTest {

    @Test
    fun `keeps up to three numbers`() {
        assertEquals("v1.2.3", shortVersion("v1.2.3"))
        assertEquals("v1.2.2", shortVersion("v1.2.2-20261002"))
        assertEquals("v1.2", shortVersion("v1.2-20260926-3"))
        assertEquals("v1.2.3", shortVersion("v1.2.3-source"))
    }
}
