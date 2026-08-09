package com.freeturn.app.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun `plain semver comparison`() {
        assertTrue(AppUpdater.isNewer("3.5.2", "3.5.1"))
        assertFalse(AppUpdater.isNewer("3.5.1", "3.5.1"))
        assertFalse(AppUpdater.isNewer("3.5.0", "3.5.1"))
    }

    @Test
    fun `fork prefix and suffix on local versionName do not break comparison`() {
        // versionName форка: "levnasru-3.5.2-beta" (и "-debug" в debug-сборке).
        assertTrue(AppUpdater.isNewer("3.5.3", "levnasru-3.5.2-beta"))
        assertFalse(AppUpdater.isNewer("3.5.2", "levnasru-3.5.2-beta"))
        assertFalse(AppUpdater.isNewer("3.5.1", "levnasru-3.5.2-beta-debug"))
    }

    @Test
    fun `v-prefixed release tag after removePrefix still compares correctly`() {
        // checkForUpdate уже делает removePrefix("v") до вызова isNewer - здесь просто чистое "3.5.2".
        assertTrue(AppUpdater.isNewer("3.5.2", "3.5.1"))
    }
}
