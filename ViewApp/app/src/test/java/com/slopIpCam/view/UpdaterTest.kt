package com.slopIpCam.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `remote newer only when strictly after install time`() {
        assertTrue(Updater.isRemoteNewer(remoteMs = 2000, installedMs = 1000))
        assertFalse(Updater.isRemoteNewer(remoteMs = 1000, installedMs = 1000))
        assertFalse(Updater.isRemoteNewer(remoteMs = 500, installedMs = 1000))
    }
}
