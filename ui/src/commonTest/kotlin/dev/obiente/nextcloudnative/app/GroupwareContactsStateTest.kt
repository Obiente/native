package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupwareContactsStateTest {
    @Test
    fun `restored edit mode reloads the selected full contact`() {
        assertTrue(
            contactEditRequiresFullLoad(
                editing = true,
                selectedContactHref = "/contacts/one.vcf",
                loadedContactHref = null,
            ),
        )
        assertFalse(
            contactEditRequiresFullLoad(
                editing = true,
                selectedContactHref = "/contacts/one.vcf",
                loadedContactHref = "/contacts/one.vcf",
            ),
        )
        assertFalse(
            contactEditRequiresFullLoad(
                editing = false,
                selectedContactHref = "/contacts/one.vcf",
                loadedContactHref = null,
            ),
        )
    }
}
