package com.ilukezippo.vcfdesk.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Placeholder proving :core:test runs; superseded by real tests in later tasks. */
class CoreWiredTest {
    @Test
    fun moduleIsWired() {
        assertEquals("VCF Desk Core", CoreInfo.NAME)
    }
}
