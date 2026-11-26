package com.ilukezippo.vcfdesk.ui.bulk

import com.ilukezippo.vcfdesk.core.model.AccountTag
import com.ilukezippo.vcfdesk.data.SourceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameTargetTest {
    private val g = AccountTag("com.google", "Me@Example.com", "Google", false)
    private val other = AccountTag("com.google", "other@example.com", "Google", false)
    private val samsung = AccountTag("com.osp.app.signin", "me@example.com", "Samsung", false)
    private val google = SourceId.Google("me@example.com")

    @Test fun phoneGoogleAccountToSignedInSameAccountIsBlocked() {
        assertTrue(isSameTarget(SourceId.Device, setOf(g), google, null))
        assertTrue(isSameTarget(SourceId.Device, setOf(samsung, g), google, null))
        assertFalse(isSameTarget(SourceId.Device, setOf(other), google, null))
        assertFalse(isSameTarget(SourceId.Device, setOf(samsung), google, null))
    }

    @Test fun signedInGoogleToPhonesSameGoogleAccountIsBlocked() {
        assertTrue(isSameTarget(google, setOf(null), SourceId.Device, g))
        assertFalse(isSameTarget(google, setOf(null), SourceId.Device, other))
        assertFalse(isSameTarget(google, setOf(null), SourceId.Device, samsung))
        assertFalse(isSameTarget(google, setOf(null), SourceId.Device, null))
    }

    @Test fun existingRulesStay() {
        assertTrue(isSameTarget(google, emptySet(), google, null))
        assertTrue(isSameTarget(google, emptySet(), SourceId.Google("ME@example.com"), null))
        assertFalse(isSameTarget(google, emptySet(), SourceId.Google("other@example.com"), null))
        assertTrue(isSameTarget(SourceId.Device, setOf(samsung), SourceId.Device, samsung))
        assertFalse(isSameTarget(SourceId.Device, setOf(samsung), SourceId.Device, g))
        assertTrue(isSameTarget(SourceId.Device, setOf(null), SourceId.Device, null))
    }
}
