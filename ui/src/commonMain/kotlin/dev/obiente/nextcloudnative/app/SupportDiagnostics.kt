package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

@Serializable
enum class SupportDiagnosticSeverity {
    Info,
    Warning,
    Error,
}

@Serializable
enum class SupportDiagnosticComponent {
    App,
    Authentication,
    Network,
    Updates,
    Files,
    Sync,
    Media,
    Storage,
    Cache,
    VirtualFiles,
    Platform,
    AdaptiveApps,
    Dav,
    Talk,
}

enum class SupportDiagnosticValuePrivacy {
    Safe,
    Identifier,
    LocalPath,
    RemotePath,
    Url,
}

data class SupportDiagnosticFieldDraft(
    val name: String,
    val value: String,
    val privacy: SupportDiagnosticValuePrivacy = SupportDiagnosticValuePrivacy.Safe,
) {
    init {
        require(SUPPORT_DIAGNOSTIC_FIELD_NAME.matches(name)) {
            "Diagnostic field names must use lowercase ASCII words."
        }
    }
}

data class SupportDiagnosticExceptionDraft(
    val type: String,
    val message: String?,
    val frames: List<SupportDiagnosticFrame>,
    val cause: SupportDiagnosticExceptionDraft? = null,
)

data class SupportDiagnosticEventDraft(
    val severity: SupportDiagnosticSeverity,
    val component: SupportDiagnosticComponent,
    val operation: String,
    val outcome: String,
    val code: String? = null,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val message: String? = null,
    val fields: List<SupportDiagnosticFieldDraft> = emptyList(),
    val exception: SupportDiagnosticExceptionDraft? = null,
) {
    init {
        require(SUPPORT_DIAGNOSTIC_OPERATION.matches(operation)) {
            "Diagnostic operations must use lowercase ASCII words."
        }
        require(SUPPORT_DIAGNOSTIC_OPERATION.matches(outcome)) {
            "Diagnostic outcomes must use lowercase ASCII words."
        }
        require(code == null || SUPPORT_DIAGNOSTIC_CODE.matches(code)) {
            "Diagnostic codes must use bounded ASCII tokens."
        }
        require(durationMillis == null || durationMillis >= 0L)
        require(attempt == null || attempt > 0)
        require(fields.size <= MAX_SUPPORT_DIAGNOSTIC_FIELDS)
    }
}

@Serializable
data class SupportDiagnosticField(
    val name: String,
    val value: String,
) {
    init {
        require(SUPPORT_DIAGNOSTIC_FIELD_NAME.matches(name))
        require(value.length <= MAX_SUPPORT_DIAGNOSTIC_FIELD_VALUE_LENGTH)
        require(value.none(Char::isISOControl))
    }
}

@Serializable
data class SupportDiagnosticFrame(
    val declaringClass: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int?,
) {
    init {
        require(declaringClass.isNotBlank())
        require(declaringClass.length <= MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH)
        require(declaringClass.none(Char::isISOControl))
        require(methodName.isNotBlank())
        require(methodName.length <= MAX_SUPPORT_DIAGNOSTIC_METHOD_LENGTH)
        require(methodName.none(Char::isISOControl))
        require(fileName == null || fileName.length <= MAX_SUPPORT_DIAGNOSTIC_FILE_NAME_LENGTH)
        require(fileName == null || fileName.none(Char::isISOControl))
        require(lineNumber == null || lineNumber >= 0)
    }
}

@Serializable
data class SupportDiagnosticException(
    val type: String,
    val messageFingerprint: String?,
    val frames: List<SupportDiagnosticFrame>,
    val cause: SupportDiagnosticException? = null,
) {
    init {
        require(type.isNotBlank())
        require(type.length <= MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH)
        require(type.none(Char::isISOControl))
        require(messageFingerprint == null || SUPPORT_DIAGNOSTIC_ALIAS.matches(messageFingerprint))
        require(frames.size <= MAX_SUPPORT_DIAGNOSTIC_EXCEPTION_FRAMES)
    }
}

@Serializable
data class SupportDiagnosticEvent(
    val schemaVersion: Int = SUPPORT_DIAGNOSTIC_EVENT_SCHEMA_VERSION,
    val sequence: Long,
    val occurredAtEpochMillis: Long,
    val severity: SupportDiagnosticSeverity,
    val component: SupportDiagnosticComponent,
    val operation: String,
    val outcome: String,
    val code: String? = null,
    val durationMillis: Long? = null,
    val attempt: Int? = null,
    val accountScope: String? = null,
    val messageFingerprint: String? = null,
    val fields: List<SupportDiagnosticField> = emptyList(),
    val exception: SupportDiagnosticException? = null,
) {
    init {
        require(schemaVersion == SUPPORT_DIAGNOSTIC_EVENT_SCHEMA_VERSION)
        require(sequence > 0L)
        require(occurredAtEpochMillis >= 0L)
        require(SUPPORT_DIAGNOSTIC_OPERATION.matches(operation))
        require(SUPPORT_DIAGNOSTIC_OPERATION.matches(outcome))
        require(code == null || SUPPORT_DIAGNOSTIC_CODE.matches(code))
        require(accountScope == null || SUPPORT_DIAGNOSTIC_ALIAS.matches(accountScope))
        require(messageFingerprint == null || SUPPORT_DIAGNOSTIC_ALIAS.matches(messageFingerprint))
        require(fields.size <= MAX_SUPPORT_DIAGNOSTIC_FIELDS)
        require(fields.all { field ->
            SUPPORT_DIAGNOSTIC_FIELD_NAME.matches(field.name) &&
                field.value.length <= MAX_SUPPORT_DIAGNOSTIC_FIELD_VALUE_LENGTH &&
                field.value.none(Char::isISOControl)
        })
    }
}

@Serializable
data class SupportDiagnosticsEnvironment(
    val appVersion: String,
    val packageVersion: String,
    val platform: String,
    val operatingSystemVersion: String,
    val architecture: String,
) {
    init {
        listOf(appVersion, packageVersion, platform, operatingSystemVersion, architecture).forEach { value ->
            require(value.length <= MAX_SUPPORT_DIAGNOSTIC_ENVIRONMENT_VALUE_LENGTH)
            require(value.none(Char::isISOControl))
        }
    }
}

fun boundedSupportDiagnosticsEnvironment(
    appVersion: String,
    packageVersion: String,
    platform: String,
    operatingSystemVersion: String,
    architecture: String,
): SupportDiagnosticsEnvironment = SupportDiagnosticsEnvironment(
    appVersion = appVersion.boundedSupportDiagnosticsEnvironmentValue(),
    packageVersion = packageVersion.boundedSupportDiagnosticsEnvironmentValue(),
    platform = platform.boundedSupportDiagnosticsEnvironmentValue(),
    operatingSystemVersion = operatingSystemVersion.boundedSupportDiagnosticsEnvironmentValue(),
    architecture = architecture.boundedSupportDiagnosticsEnvironmentValue(),
)

private fun String.boundedSupportDiagnosticsEnvironmentValue(): String =
    filterNot(Char::isISOControl)
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Unknown" }
        .take(MAX_SUPPORT_DIAGNOSTIC_ENVIRONMENT_VALUE_LENGTH)

data class SupportDiagnosticsSummary(
    val available: Boolean,
    val eventCount: Int,
    val warningCount: Int,
    val errorCount: Int,
    val oldestEventAtEpochMillis: Long?,
    val newestEventAtEpochMillis: Long?,
    val components: Set<SupportDiagnosticComponent>,
    val storedBytes: Long,
    val includedFiles: List<String>,
    val recentEvents: List<SupportDiagnosticPreviewEvent> = emptyList(),
    val explanation: String? = null,
)

data class SupportDiagnosticPreviewEvent(
    val occurredAtEpochMillis: Long,
    val severity: SupportDiagnosticSeverity,
    val component: SupportDiagnosticComponent,
    val operation: String,
    val outcome: String,
    val code: String?,
)

sealed interface SupportDiagnosticsExportResult {
    data class Exported(val destination: String) : SupportDiagnosticsExportResult
    data object Cancelled : SupportDiagnosticsExportResult
    data class Failed(val message: String) : SupportDiagnosticsExportResult
    data class Unsupported(val reason: String) : SupportDiagnosticsExportResult
}

sealed interface SupportDiagnosticsSubmissionState {
    data object Initializing : SupportDiagnosticsSubmissionState
    data object AccountRequired : SupportDiagnosticsSubmissionState
    data object Idle : SupportDiagnosticsSubmissionState
    data class BlockedByAnotherAccount(val message: String) : SupportDiagnosticsSubmissionState
    data object Packaging : SupportDiagnosticsSubmissionState
    data object Cancelling : SupportDiagnosticsSubmissionState
    data object DeletingSubmittedReport : SupportDiagnosticsSubmissionState
    data class Uploading(val progress: Float?) : SupportDiagnosticsSubmissionState {
        init {
            require(progress == null || progress in 0f..1f)
        }
    }
    data class RetryableFailure(val message: String, val outcomeAmbiguous: Boolean) :
        SupportDiagnosticsSubmissionState
    data class Rejected(val message: String) : SupportDiagnosticsSubmissionState
    data object Cancelled : SupportDiagnosticsSubmissionState
    data class SubmittedReport(
        val recordId: String,
        val supportCode: String,
        val createdAt: String,
        val retentionUntil: String,
        val status: String,
        val updatedAt: String? = null,
        val messages: List<SupportDiagnosticsMessage> = emptyList(),
        val unreadMaintainerMessages: Int = 0,
        val statusChanged: Boolean = false,
        val conversationLoading: Boolean = false,
        val conversationError: String? = null,
    )
    data class Submitted(val reports: List<SubmittedReport>) : SupportDiagnosticsSubmissionState {
        init {
            require(reports.isNotEmpty())
        }

        val supportCode: String get() = reports.first().supportCode
        val recordId: String get() = reports.first().recordId
        val retentionUntil: String get() = reports.first().retentionUntil
    }
    data class Unsupported(val reason: String) : SupportDiagnosticsSubmissionState
}

enum class SupportDiagnosticsMessageAuthor {
    Maintainer,
    Reporter,
}

data class SupportDiagnosticsMessage(
    val id: String,
    val author: SupportDiagnosticsMessageAuthor,
    val body: String,
    val createdAt: String,
)

sealed interface SupportDiagnosticsConversationResult {
    data object Updated : SupportDiagnosticsConversationResult
    data class ReplyDeliveryUnknown(val message: String) : SupportDiagnosticsConversationResult
    data class Failed(val message: String) : SupportDiagnosticsConversationResult
    data class Unsupported(val reason: String) : SupportDiagnosticsConversationResult
}

sealed interface SupportDiagnosticsDeletionResult {
    data object Deleted : SupportDiagnosticsDeletionResult
    data class Failed(val message: String) : SupportDiagnosticsDeletionResult
    data class Unsupported(val reason: String) : SupportDiagnosticsDeletionResult
}

@Serializable
internal data class SupportIntakeRelease(
    val version: String,
    val channel: String,
    val platform: String,
    val osVersion: String,
    val architecture: String,
)

@Serializable
internal data class SupportIntakeMetadata(
    val contractVersion: Int = SUPPORT_INTAKE_CONTRACT_VERSION,
    val productId: String = SUPPORT_INTAKE_PRODUCT_ID,
    val requestType: String = "bug",
    val title: String,
    val description: String,
    val contact: String = "",
    val source: String = "app",
    val release: SupportIntakeRelease,
    val privacyAccepted: Boolean = true,
)

@Serializable
internal data class SupportIntakeReceipt(
    val contractVersion: Int,
    val supportCode: String,
    val status: String,
    val statusUrl: String,
    val deletionUrl: String,
    val createdAt: String,
    val retentionUntil: String,
)

internal const val SUPPORT_INTAKE_CONTRACT_VERSION = 1
internal const val SUPPORT_INTAKE_PRODUCT_ID = "nextcloud-native"
internal const val DEFAULT_OBIENTE_SUPPORT_URL = "https://support.obiente.org"

internal class SupportDiagnosticSanitizer(
    private val pseudonymize: (String) -> String,
) {
    private val privateValues = linkedSetOf<String>()

    fun registerPrivateValue(value: String?) {
        value?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_PRIVATE_VALUE_LENGTH }
            ?.let { privateValue ->
                privateValues.remove(privateValue)
                privateValues.add(privateValue)
                while (privateValues.size > MAX_REGISTERED_PRIVATE_VALUES) {
                    privateValues.remove(privateValues.first())
                }
            }
    }

    fun sanitize(
        sequence: Long,
        occurredAtEpochMillis: Long,
        accountScope: String? = null,
        draft: SupportDiagnosticEventDraft,
    ): SupportDiagnosticEvent = SupportDiagnosticEvent(
        sequence = sequence,
        occurredAtEpochMillis = occurredAtEpochMillis,
        severity = draft.severity,
        component = draft.component,
        operation = draft.operation,
        outcome = draft.outcome,
        code = draft.code?.let { sanitizeText(it, MAX_SUPPORT_DIAGNOSTIC_CODE_LENGTH) },
        durationMillis = draft.durationMillis,
        attempt = draft.attempt,
        accountScope = accountScope,
        messageFingerprint = draft.message
            ?.takeIf(String::isNotBlank)
            ?.let { privateAlias("message", it.take(MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH)) },
        fields = draft.fields.map { field ->
            SupportDiagnosticField(
                name = field.name,
                value = sanitizeField(field),
            )
        },
        exception = draft.exception?.let { sanitizeException(it, 0) },
    )

    fun sanitizeUserDescription(value: String): String =
        sanitizeText(value, MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH)

    fun sanitizeFields(fields: List<SupportDiagnosticFieldDraft>): List<SupportDiagnosticField> {
        require(fields.size <= MAX_SUPPORT_DIAGNOSTIC_FIELDS)
        return fields.map { field ->
            SupportDiagnosticField(name = field.name, value = sanitizeField(field))
        }
    }

    private fun sanitizeField(field: SupportDiagnosticFieldDraft): String = when (field.privacy) {
        SupportDiagnosticValuePrivacy.Safe -> sanitizeText(field.value, MAX_SUPPORT_DIAGNOSTIC_FIELD_VALUE_LENGTH)
        SupportDiagnosticValuePrivacy.Identifier -> privateAlias("id", field.value)
        SupportDiagnosticValuePrivacy.LocalPath -> privateAlias("local-path", field.value)
        SupportDiagnosticValuePrivacy.RemotePath -> privateAlias("remote-path", field.value)
        SupportDiagnosticValuePrivacy.Url -> privateAlias("url", field.value)
    }

    private fun sanitizeException(
        draft: SupportDiagnosticExceptionDraft,
        depth: Int,
    ): SupportDiagnosticException {
        val boundedFrames = draft.frames.take(MAX_SUPPORT_DIAGNOSTIC_EXCEPTION_FRAMES).map { frame ->
            boundedSupportDiagnosticFrame(
                declaringClass = sanitizeCodeToken(frame.declaringClass, MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH),
                methodName = sanitizeCodeToken(frame.methodName, MAX_SUPPORT_DIAGNOSTIC_METHOD_LENGTH),
                fileName = frame.fileName
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.let { sanitizeCodeToken(it, MAX_SUPPORT_DIAGNOSTIC_FILE_NAME_LENGTH) },
                lineNumber = frame.lineNumber?.takeIf { it >= 0 },
            )
        }
        return SupportDiagnosticException(
            type = sanitizeCodeToken(draft.type, MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH),
            messageFingerprint = draft.message
                ?.takeIf(String::isNotBlank)
                ?.let { privateAlias("exception-message", it.take(MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH)) },
            frames = boundedFrames,
            cause = draft.cause
                ?.takeIf { depth + 1 < MAX_SUPPORT_DIAGNOSTIC_CAUSE_DEPTH }
                ?.let { sanitizeException(it, depth + 1) },
        )
    }

    private fun sanitizeText(raw: String, maximumLength: Int): String {
        var value = raw.take(MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH)
            .normalizeExternalSpacingAndFormatting()
            .replace(SENSITIVE_HEADER_LINE) { match -> "${match.groupValues[1]}=<secret>" }
            .replace(CONTROL_CHARACTERS) { match ->
                when (match.value) {
                    "\n", "\r", "\t" -> " "
                    else -> ""
                }
            }
        privateValues.sortedByDescending(String::length).forEach { privateValue ->
            val alias = privateAlias("private", privateValue)
            value = if (privateValue.length >= MIN_UNBOUNDED_PRIVATE_VALUE_LENGTH) {
                value.replace(privateValue, alias, ignoreCase = true)
            } else {
                value.replaceShortPrivateValue(privateValue, alias)
            }
        }
        value = value.replace(AUTHORIZATION_VALUE) { match ->
            "${match.groupValues[1]}=<secret>"
        }
        value = value.replace(BEARER_OR_BASIC_VALUE, "<secret>")
        value = value.replace(URL_VALUE) { match -> privateAlias("url", match.value) }
        value = value.replace(EMAIL_VALUE) { match -> privateAlias("email", match.value.lowercase()) }
        value = value.replace(WINDOWS_PATH_VALUE) { match -> privateAlias("local-path", match.value) }
        value = value.replace(UNIX_PATH_VALUE) { match -> privateAlias("local-path", match.value) }
        value = value.replace(RELATIVE_PATH_VALUE) { match -> privateAlias("remote-path", match.value) }
        value = value.replace(FILE_NAME_VALUE) { match -> privateAlias("file", match.value) }
        value = value.replace(IPV6_ADDRESS_VALUE) { match -> privateAlias("address", match.value) }
        value = value.replace(IP_ADDRESS_VALUE) { match -> privateAlias("address", match.value) }
        value = value.replace(UUID_VALUE) { match -> privateAlias("id", match.value.lowercase()) }
        value = value.replace(LONG_HEX_VALUE) { match -> privateAlias("id", match.value.lowercase()) }
        value = value.replace(LONG_SECRET_VALUE) { match -> privateAlias("secret", match.value) }
        return value.replace(WHITESPACE, " ").trim().take(maximumLength)
    }

    private fun String.normalizeExternalSpacingAndFormatting(): String = buildString(length) {
        this@normalizeExternalSpacingAndFormatting.forEach { character ->
            append(
                when (character.category) {
                    CharCategory.SPACE_SEPARATOR,
                    CharCategory.FORMAT,
                    -> ' '
                    CharCategory.LINE_SEPARATOR,
                    CharCategory.PARAGRAPH_SEPARATOR,
                    -> '\n'
                    else -> character
                },
            )
        }
    }

    private fun privateAlias(kind: String, value: String): String =
        "<$kind:${pseudonymize(value.take(MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH)).take(SUPPORT_DIAGNOSTIC_ALIAS_LENGTH)}>"

    private fun sanitizeCodeToken(value: String, maximumLength: Int): String = value
        .filter { character ->
            character.isLetterOrDigit() || character in setOf('.', '_', '-', '$')
        }
        .ifBlank { "Unknown" }
        .take(maximumLength)

    private fun String.replaceShortPrivateValue(privateValue: String, alias: String): String = buildString(length) {
        var cursor = 0
        while (cursor < this@replaceShortPrivateValue.length) {
            val match = this@replaceShortPrivateValue.indexOf(privateValue, cursor, ignoreCase = true)
            if (match < 0) {
                append(this@replaceShortPrivateValue, cursor, this@replaceShortPrivateValue.length)
                break
            }
            append(this@replaceShortPrivateValue, cursor, match)
            val end = match + privateValue.length
            val startsAtBoundary = match == 0 || !this@replaceShortPrivateValue[match - 1].isLetterOrDigit()
            val endsAtBoundary = end == this@replaceShortPrivateValue.length ||
                !this@replaceShortPrivateValue[end].isLetterOrDigit()
            if (startsAtBoundary && endsAtBoundary) {
                append(alias)
            } else {
                append(this@replaceShortPrivateValue, match, end)
            }
            cursor = end
        }
    }
}

internal fun boundedSupportDiagnosticFrame(
    declaringClass: String,
    methodName: String,
    fileName: String?,
    lineNumber: Int?,
): SupportDiagnosticFrame = SupportDiagnosticFrame(
    declaringClass = declaringClass.sanitizeSupportDiagnosticCodeToken(MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH),
    methodName = methodName.sanitizeSupportDiagnosticCodeToken(MAX_SUPPORT_DIAGNOSTIC_METHOD_LENGTH),
    fileName = fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.sanitizeSupportDiagnosticCodeToken(MAX_SUPPORT_DIAGNOSTIC_FILE_NAME_LENGTH),
    lineNumber = lineNumber?.takeIf { it >= 0 },
)

private fun String.sanitizeSupportDiagnosticCodeToken(maximumLength: Int): String =
    filter { character ->
        character.isLetterOrDigit() || character in setOf('.', '_', '-', '$')
    }
        .ifBlank { "Unknown" }
        .take(maximumLength)

internal const val SUPPORT_DIAGNOSTIC_EVENT_SCHEMA_VERSION = 1
internal const val MAX_SUPPORT_DIAGNOSTIC_FIELDS = 24
internal const val MAX_SUPPORT_DIAGNOSTIC_EVENTS = 1_000
internal const val MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES = 2L * 1024L * 1024L
internal const val MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS = 7L * 24L * 60L * 60L * 1_000L
internal const val MAX_SUPPORT_REPRODUCTION_STEPS_LENGTH = 4_096
internal val SUPPORT_BUNDLE_INCLUDED_FILES = listOf(
    "README.txt",
    "report.json",
    "events.jsonl",
    "manifest.json",
)

private const val MIN_UNBOUNDED_PRIVATE_VALUE_LENGTH = 3
private const val MAX_PRIVATE_VALUE_LENGTH = 4_096
private const val MAX_REGISTERED_PRIVATE_VALUES = 128
private const val MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH = 16_384
internal const val MAX_SUPPORT_DIAGNOSTIC_FIELD_VALUE_LENGTH = 512
private const val MAX_SUPPORT_DIAGNOSTIC_CODE_LENGTH = 96
private const val MAX_SUPPORT_DIAGNOSTIC_EXCEPTION_FRAMES = 16
private const val MAX_SUPPORT_DIAGNOSTIC_CAUSE_DEPTH = 4
internal const val MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH = 180
internal const val MAX_SUPPORT_DIAGNOSTIC_METHOD_LENGTH = 120
internal const val MAX_SUPPORT_DIAGNOSTIC_FILE_NAME_LENGTH = 120
internal const val MAX_SUPPORT_DIAGNOSTIC_ENVIRONMENT_VALUE_LENGTH = 160
internal const val SUPPORT_DIAGNOSTIC_ALIAS_LENGTH = 16

internal val SUPPORT_DIAGNOSTIC_FIELD_NAME = Regex("^[a-z][a-z0-9_.-]{0,63}$")
private val SUPPORT_DIAGNOSTIC_OPERATION = Regex("^[a-z][a-z0-9._-]{0,79}$")
private val SUPPORT_DIAGNOSTIC_CODE = Regex("^[A-Za-z0-9._:-]{1,96}$")
private val SUPPORT_DIAGNOSTIC_ALIAS = Regex("^<[a-z-]+:[a-f0-9]{16}>$")
private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")
private val WHITESPACE = Regex("\\s+")
private const val SENSITIVE_CREDENTIAL_LABEL_PATTERN =
    "authorization|proxy-authorization|cookie|set-cookie|" +
        "(?:[a-z][a-z0-9_-]*)?(?:password|passphrase|passwd|passcode|pwd|token|secret|credential)s?" +
        "(?:[-_](?:confirmation|confirm|value))?|" +
        "(?:api|private|secret|client|consumer)[-_ ]?key"
private val SENSITIVE_HEADER_LINE = Regex(
    "(?im)[\"']?\\b($SENSITIVE_CREDENTIAL_LABEL_PATTERN)\\b[\"']?" +
        "\\s*(?::|=|\\bis\\b|\\bwas\\b)\\s*[^\\r\\n]*",
)
private val AUTHORIZATION_VALUE = Regex(
    "(?i)\\b($SENSITIVE_CREDENTIAL_LABEL_PATTERN)\\b\\s*[:=]\\s*" +
        "(?:(?:bearer|basic)\\s+)?[^\\s,;]+",
)
private val BEARER_OR_BASIC_VALUE = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/=_-]{8,}")
private val URL_VALUE = Regex("(?i)\\b(?:https?|dav|webdav)://[^\\s\"'<>]+")
private val EMAIL_VALUE = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
private val WINDOWS_PATH_VALUE = Regex("(?i)(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\r\\n\"<>|,;)]{2,}")
private val UNIX_PATH_VALUE = Regex(
    "(?<![A-Za-z0-9])/(?:[^\\s/:\"'<>|]+/)*[^\\s/:\"'<>|,;)]+",
)
private val RELATIVE_PATH_VALUE = Regex("(?<![A-Za-z0-9:/])(?:[^\\s/:]+/)+[^\\s,;)]*")
private val FILE_NAME_VALUE = Regex(
    "(?i)(?<![A-Za-z0-9._-])[^\\s/\\\\]+\\.(?:[A-Z][A-Z0-9]{0,11}|7Z)(?![A-Za-z0-9._-])",
)
private val IP_ADDRESS_VALUE = Regex(
    "(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9])",
)
private val IPV6_ADDRESS_VALUE = Regex(
    "(?i)(?<![A-Za-z0-9:])(?:\\[(?=[0-9A-F:.%]*:)[0-9A-F:.]+(?:%[A-Za-z0-9._-]+)?\\]|" +
        "(?=[0-9A-F:.%]*:)(?:[0-9A-F]{0,4}:){2,7}(?:[0-9A-F]{0,4}|" +
        "(?:[0-9]{1,3}\\.){3}[0-9]{1,3})(?:%[A-Za-z0-9._-]+)?)(?![A-Za-z0-9:])",
)
private val UUID_VALUE = Regex(
    "(?i)(?<![A-F0-9])[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12}(?![A-F0-9])",
)
private val LONG_HEX_VALUE = Regex("(?i)(?<![A-F0-9])[A-F0-9]{32,}(?![A-F0-9])")
private val LONG_SECRET_VALUE = Regex("(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{40,}(?![A-Za-z0-9+/=_-])")
