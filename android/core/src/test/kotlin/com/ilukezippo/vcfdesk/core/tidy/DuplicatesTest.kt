package com.ilukezippo.vcfdesk.core.tidy

import com.ilukezippo.vcfdesk.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private val device = AccountTag("device", "phone", "Phone", false)
    private val google = AccountTag("google", "name@example.com", "Google", false)

    private fun c(
        id: String,
        name: String = "Name 1",
        account: AccountTag? = null,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        photo: ByteArray? = null,
    ) = Contact(
        id = id,
        account = account,
        name = Name(display = name),
        phones = phones.map { Typed("mobile", it) },
        emails = emails.map { Typed("home", it) },
        photo = photo,
    )

    @Test fun sameNameSameNumbersIsDuplicate() {
        val a = c("1", "Name 1", phones = listOf("55555555"), emails = listOf("n1@example.com"))
        val b = c("2", "  name 1 ", phones = listOf("55555555"), emails = listOf("N1@example.com"))
        val other = c("3", "Name 1", phones = listOf("99999999"), emails = listOf("n1@example.com"))
        val groups = Duplicates.groups(listOf(a, other, b))
        assertEquals(listOf(listOf("1", "2")), groups.map { g -> g.map { it.id } })
    }

    @Test fun differentAccountsStillGroupOnlyWithinSameAccount() {
        val d = c("1", "Name 2", device, phones = listOf("66666666"))
        val g = c("2", "Name 2", google, phones = listOf("66666666"))
        val n = c("3", "Name 2", null, phones = listOf("66666666"))
        val n2 = c("4", "Name 2", null, phones = listOf("66666666"))
        val groups = Duplicates.groups(listOf(d, g, n, n2))
        assertEquals(1, groups.size)
        assertEquals(listOf("3", "4"), groups[0].map { it.id })
        assertTrue(groups.all { g -> g.all { it.account == g[0].account } })
    }

    @Test fun singletonsAreNotReturnedAndOrderFollowsFirstAppearance() {
        val x1 = c("1", "Name 3", emails = listOf("A@example.com"))
        val lone = c("2", "Name 4")
        val y1 = c("3", "Name 5", phones = listOf("99999999"))
        val x2 = c("4", "name 3", emails = listOf("a@example.com "))
        val y2 = c("5", "Name 5", phones = listOf("99999999"))
        val groups = Duplicates.groups(listOf(x1, lone, y1, x2, y2))
        assertEquals(listOf(listOf("1", "4"), listOf("3", "5")), groups.map { g -> g.map { it.id } })
    }

    @Test fun separatorsStillMatch() {
        val a = c("1", "Name 8", phones = listOf("5555 5555"))
        val b = c("2", "Name 8", phones = listOf("(5555) 5555"))
        assertEquals(listOf(listOf("1", "2")), Duplicates.groups(listOf(a, b)).map { g -> g.map { it.id } })
    }

    @Test fun phoneWithNoDigitsIsDroppedFromKey() {
        val withJunk = c("1", "Name 9", phones = listOf("call me"))
        val bare = c("2", "Name 9")
        assertEquals(listOf(listOf("1", "2")), Duplicates.groups(listOf(withJunk, bare)).map { g -> g.map { it.id } })
    }

    @Test fun arabicIndicDigitsMatchAsciiDigits() {
        val arabic = c("1", "Name 10", phones = listOf("\u0665".repeat(8)))
        val ascii = c("2", "Name 10", phones = listOf("55555555"))
        assertEquals(listOf(listOf("1", "2")), Duplicates.groups(listOf(arabic, ascii)).map { g -> g.map { it.id } })
    }

    @Test fun arabicIndicSameNumberMatchesArabicIndic() {
        val first = c("1", "Name 12", phones = listOf("\u0669".repeat(8)))
        val second = c("2", "Name 12", phones = listOf("\u0669".repeat(8)))
        assertEquals(listOf(listOf("1", "2")), Duplicates.groups(listOf(first, second)).map { g -> g.map { it.id } })
    }

    @Test fun arabicIndicDifferentNumbersDoNotMatch() {
        val nine = c("1", "Name 11", phones = listOf("\u0669".repeat(8)))
        val six = c("2", "Name 11", phones = listOf("\u0666".repeat(8)))
        assertTrue(Duplicates.groups(listOf(nine, six)).isEmpty())
    }

    @Test fun emptyKeysAreNeverGrouped() {
        val a = c("1", "")
        val b = c("2", "")
        val d = c("3", "")
        assertTrue(Duplicates.groups(listOf(a, b, d)).isEmpty())
    }

    @Test fun keeperPrefersPhoto() {
        val withPhotoOnly = c("1", "Name 6", phones = listOf("55555555"), photo = byteArrayOf(1))
        val twoFields = c("2", "Name 6", phones = listOf("55555555"), emails = listOf("n6@example.com"))
        assertSame(withPhotoOnly, Duplicates.keeper(listOf(twoFields, withPhotoOnly)))
    }

    @Test fun keeperTieGoesToEarliest() {
        val a = c("1", "Name 7", phones = listOf("55555555"), emails = listOf("n7@example.com"))
        val b = c("2", "Name 7", phones = listOf("55555555"), emails = listOf("n7@example.com"))
        assertSame(a, Duplicates.keeper(listOf(a, b)))
    }
}
