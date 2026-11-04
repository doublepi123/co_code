package io.cocode.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoxSettingsTest {
    @Test
    fun `upgrading an old runtime is required before opening the new model center route`() {
        listOf(null, "", "0.1.15", "0.1.20", "0.1.6-pw.6", "unknown").forEach { assertFalse(supportsCoxModelCenter(it)) }
        listOf("0.1.21", "0.1.29", "0.2.0", "1.0.0").forEach { assertTrue(supportsCoxModelCenter(it)) }
    }
}
