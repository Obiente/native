package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportDiagnosticsTest {
    private val sanitizer = SupportDiagnosticSanitizer { value ->
        publicContentSha256(("test-key\u0000$value").encodeToByteArray())
    }

    @Test
    fun sanitizesSecretsUrlsAccountsAndPathsBeforeCreatingEvent() {
        val server = "https://cloud.example.test/nextcloud"
        val login = "person@example.test"
        val bearerCredential = "fixture-" + "credential-".repeat(4)
        sanitizer.registerPrivateValue(server)
        sanitizer.registerPrivateValue(login)

        val event = sanitizer.sanitize(
            sequence = 1L,
            occurredAtEpochMillis = 1_000L,
            draft = SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Network,
                operation = "request.execute",
                outcome = "failed",
                code = "HTTP:500",
                message = "Authorization: Bearer $bearerCredential\n" +
                    "Could not read Photos/Family/private.jpg or single-secret.nef at $server for $login",
                fields = listOf(
                    SupportDiagnosticFieldDraft(
                        "local_path",
                        "D:\\Fixtures\\Private Person\\Nextcloud Native\\Photos\\private.jpg",
                        SupportDiagnosticValuePrivacy.LocalPath,
                    ),
                    SupportDiagnosticFieldDraft(
                        "remote_path",
                        "Photos/Family/private.jpg",
                        SupportDiagnosticValuePrivacy.RemotePath,
                    ),
                    SupportDiagnosticFieldDraft(
                        "request_url",
                        "$server/remote.php/dav/files/person/private.jpg?token=secret",
                        SupportDiagnosticValuePrivacy.Url,
                    ),
                ),
            ),
        )

        val serializedEvidence = buildString {
            append(event.messageFingerprint)
            event.fields.forEach { append(it.value) }
        }
        listOf(
            "cloud.example.test",
            "person@example.test",
            "Private Person",
            "Family",
            "private.jpg",
            bearerCredential,
            "token=secret",
            "Photos/Family",
            "single-secret.nef",
        ).forEach { privateValue ->
            assertFalse(privateValue in serializedEvidence, "$privateValue remained in $serializedEvidence")
        }
        assertTrue(requireNotNull(event.messageFingerprint).startsWith("<message:"))
        assertTrue(event.fields.single { it.name == "local_path" }.value.startsWith("<local-path:"))
        assertTrue(event.fields.single { it.name == "remote_path" }.value.startsWith("<remote-path:"))
        assertTrue(event.fields.single { it.name == "request_url" }.value.startsWith("<url:"))
    }

    @Test
    fun aliasesRemainStableWithoutExposingTheOriginalValue() {
        val first = sanitizer.sanitize(
            sequence = 1L,
            occurredAtEpochMillis = 1L,
            draft = fieldEvent("Photos/Camera/image.jpg"),
        ).fields.single().value
        val second = sanitizer.sanitize(
            sequence = 2L,
            occurredAtEpochMillis = 2L,
            draft = fieldEvent("Photos/Camera/image.jpg"),
        ).fields.single().value
        val different = sanitizer.sanitize(
            sequence = 3L,
            occurredAtEpochMillis = 3L,
            draft = fieldEvent("Photos/Camera/other.jpg"),
        ).fields.single().value

        assertEquals(first, second)
        assertTrue(first != different)
        assertFalse("image.jpg" in first)
    }

    @Test
    fun sanitizesBareBracketedAndZoneQualifiedIpv6Addresses() {
        val addresses = listOf(
            "2001:db8::1",
            "[2001:db8:0:1::20]",
            "fe80::1234%wlan0",
            "::ffff:192.0.2.10",
        )

        val sanitized = sanitizer.sanitizeUserDescription(
            addresses.joinToString(prefix = "Servers: ", separator = ", "),
        )

        addresses.forEach { address -> assertFalse(address in sanitized, address) }
        assertTrue("<address:" in sanitized)
    }

    @Test
    fun redactsSingleComponentAbsolutePaths() {
        val privatePaths = listOf("/Family", "/TaxRecords")

        val sanitized = sanitizer.sanitizeUserDescription(
            "Opening ${privatePaths.joinToString(" and ")} fails.",
        )

        privatePaths.forEach { path -> assertFalse(path in sanitized, path) }
        assertEquals(2, "<local-path:".toRegex().findAll(sanitized).count())
    }

    @Test
    fun redactsCompleteExplicitlyLabeledMultiwordCredentials() {
        val words = listOf("correct", "horse", "battery", "staple")

        val sanitized = sanitizer.sanitizeUserDescription(
            "Login failed\npassphrase=${words.joinToString(" ")}\nRetry still fails",
        )

        words.forEach { word -> assertFalse(word in sanitized, word) }
        assertTrue("passphrase=<secret>" in sanitized)
        assertTrue("Retry still fails" in sanitized)
    }

    @Test
    fun redactsQuotedJsonCredentialAssignments() {
        val credential = listOf("fixture", "credential").joinToString("-")

        val sanitized = sanitizer.sanitizeUserDescription(
            "{\"user\":\"person\",\"password\":\"$credential\"}",
        )

        assertFalse(credential in sanitized)
        assertTrue("password=<secret>" in sanitized)
    }

    @Test
    fun redactsNaturalLanguageCredentialDisclosures() {
        val credentialWords = listOf("correct", "horse", "battery", "staple")

        val sanitized = sanitizer.sanitizeUserDescription(
            "Login failed\nmy password is ${credentialWords.joinToString(" ")}\nRetry still fails",
        )

        credentialWords.forEach { word -> assertFalse(word in sanitized, word) }
        assertTrue("my password=<secret>" in sanitized)
        assertTrue("Retry still fails" in sanitized)
    }

    @Test
    fun sanitizesExceptionMessagesAndBoundsFramesAndCauses() {
        val privatePath = "/srv/fixtures/Pictures/private.jpg"
        val frames = (1..40).map { index ->
            SupportDiagnosticFrame(
                declaringClass = "dev.obiente.Example$index",
                methodName = "operation$index",
                fileName = "/private/source/Example$index.kt",
                lineNumber = index,
            )
        }
        val event = sanitizer.sanitize(
            sequence = 1L,
            occurredAtEpochMillis = 1L,
            draft = SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Files,
                operation = "file.open",
                outcome = "failed",
                exception = SupportDiagnosticExceptionDraft(
                    type = "java.io.IOException",
                    message = "Could not read $privatePath",
                    frames = frames,
                    cause = SupportDiagnosticExceptionDraft(
                        type = "java.lang.IllegalStateException",
                        message = "password=hunter2",
                        frames = emptyList(),
                    ),
                ),
            ),
        )

        val exception = requireNotNull(event.exception)
        assertEquals(16, exception.frames.size)
        assertTrue(requireNotNull(exception.messageFingerprint).startsWith("<exception-message:"))
        assertEquals("Example1.kt", exception.frames.first().fileName)
        assertTrue(requireNotNull(exception.cause?.messageFingerprint).startsWith("<exception-message:"))
    }

    @Test
    fun userDescriptionUsesTheSamePrivacyBoundaryAndLengthLimit() {
        val privateValue = "person@example.test"
        val description = sanitizer.sanitizeUserDescription(
            "Contact $privateValue at https://cloud.example.test/secret " + "x".repeat(8_000),
        )

        assertFalse(privateValue in description)
        assertFalse("cloud.example.test" in description)
        assertTrue(description.length <= MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)
    }

    private fun fieldEvent(path: String) = SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.Sync,
        operation = "sync.plan",
        outcome = "conflict",
        fields = listOf(
            SupportDiagnosticFieldDraft(
                name = "path",
                value = path,
                privacy = SupportDiagnosticValuePrivacy.RemotePath,
            ),
        ),
    )
}
