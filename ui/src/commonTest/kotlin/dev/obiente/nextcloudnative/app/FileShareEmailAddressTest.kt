package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileShareEmailAddressTest {
    @Test
    fun completeAddressesAreAcceptedWithSurroundingWhitespaceRemoved() {
        listOf(
            "reader@example.test",
            "first.last+share@mail.example.test",
            "o'brien_42@sub-domain.example.test",
            "léa@bücher.example.test",
        ).forEach { address ->
            assertEquals(
                FileShareEmailAddressValidation.Valid(address),
                validateFileShareEmailAddress("  $address\t"),
                address,
            )
        }
    }

    @Test
    fun incompleteOrMalformedAddressesExplainWhatIsWrong() {
        mapOf(
            "" to "Enter an email address.",
            "   " to "Enter an email address.",
            "reader" to "Enter a complete email address, such as name@example.com.",
            "reader@@example.test" to "An email address can contain only one @.",
            "a@b@example.test" to "An email address can contain only one @.",
            "@example.test" to "Enter the name before the @.",
            "reader@" to "Enter the domain after the @.",
            "reader@example" to "Enter a complete domain after the @, such as example.com.",
            ".reader@example.test" to "The part before the @ contains characters an email address cannot use.",
            "reader.@example.test" to "The part before the @ contains characters an email address cannot use.",
            "re..ader@example.test" to "The part before the @ contains characters an email address cannot use.",
            "re ader@example.test" to "The part before the @ contains characters an email address cannot use.",
            "\"reader\"@example.test" to "The part before the @ contains characters an email address cannot use.",
            "<reader>@example.test" to "The part before the @ contains characters an email address cannot use.",
            "reader@-example.test" to "The domain after the @ is not valid.",
            "reader@example-.test" to "The domain after the @ is not valid.",
            "reader@example..test" to "The domain after the @ is not valid.",
            "reader@example.test." to "The domain after the @ is not valid.",
            "reader@exa_mple.test" to "The domain after the @ is not valid.",
            "reader@192.0.2.1" to "The domain after the @ is not valid.",
            "reader@[192.0.2.1]" to "The domain after the @ is not valid.",
        ).forEach { (input, reason) ->
            assertEquals(
                FileShareEmailAddressValidation.Invalid(reason),
                validateFileShareEmailAddress(input),
                input,
            )
        }
    }

    @Test
    fun oversizedAddressesAndPartsAreRejected() {
        val tooLong = "${"a".repeat(64)}@${"b".repeat(63)}.${"c".repeat(63)}.${"d".repeat(63)}.test"
        assertEquals(
            FileShareEmailAddressValidation.Invalid("This email address is too long."),
            validateFileShareEmailAddress(tooLong),
        )
        assertEquals(
            FileShareEmailAddressValidation.Invalid("This email address is too long."),
            validateFileShareEmailAddress("${"a".repeat(65)}@example.test"),
        )
        assertEquals(
            FileShareEmailAddressValidation.Invalid("The domain after the @ is not valid."),
            validateFileShareEmailAddress("reader@${"b".repeat(64)}.test"),
        )
        assertIs<FileShareEmailAddressValidation.Valid>(
            validateFileShareEmailAddress("${"a".repeat(64)}@${"b".repeat(63)}.test"),
        )
    }

    @Test
    fun typedRecipientUsesTheAddressAsTheEmailShareIdentity() {
        assertEquals(
            FileShareRecipient("reader@example.test", "reader@example.test", FileShareTarget.Email, exact = true),
            typedFileShareEmailRecipient(" reader@example.test "),
        )
        assertNull(typedFileShareEmailRecipient("reader@example"))
        assertNull(typedFileShareEmailRecipient("Reader Name"))
    }
}
