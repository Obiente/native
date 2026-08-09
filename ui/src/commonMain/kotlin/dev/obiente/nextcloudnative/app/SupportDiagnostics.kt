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
)

@Serializable
data class SupportDiagnosticFrame(
    val declaringClass: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int?,
)

@Serializable
data class SupportDiagnosticException(
    val type: String,
    val messageFingerprint: String?,
    val frames: List<SupportDiagnosticFrame>,
    val cause: SupportDiagnosticException? = null,
) {
    init {
        require(type.length <= MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH)
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
        require(code == null || code.length <= MAX_SUPPORT_DIAGNOSTIC_CODE_LENGTH && code.none(Char::isISOControl))
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
)

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

internal class SupportDiagnosticSanitizer(
    private val pseudonymize: (String) -> String,
) {
    private val privateValues = linkedSetOf<String>()

    fun registerPrivateValue(value: String?) {
        value?.trim()
            ?.takeIf { it.length in MIN_PRIVATE_VALUE_LENGTH..MAX_PRIVATE_VALUE_LENGTH }
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
            SupportDiagnosticFrame(
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
            .replace(SENSITIVE_HEADER_LINE) { match -> "${match.groupValues[1]}=<secret>" }
            .replace(CONTROL_CHARACTERS) { match ->
                when (match.value) {
                    "\n", "\r", "\t" -> " "
                    else -> ""
                }
            }
        privateValues.sortedByDescending(String::length).forEach { privateValue ->
            value = value.replace(privateValue, privateAlias("private", privateValue), ignoreCase = true)
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

    private fun privateAlias(kind: String, value: String): String =
        "<$kind:${pseudonymize(value.take(MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH)).take(SUPPORT_DIAGNOSTIC_ALIAS_LENGTH)}>"

    private fun sanitizeCodeToken(value: String, maximumLength: Int): String = value
        .filter { character ->
            character.isLetterOrDigit() || character in setOf('.', '_', '-', '$')
        }
        .ifBlank { "Unknown" }
        .take(maximumLength)
}

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

private const val MIN_PRIVATE_VALUE_LENGTH = 3
private const val MAX_PRIVATE_VALUE_LENGTH = 4_096
private const val MAX_REGISTERED_PRIVATE_VALUES = 128
private const val MAX_SUPPORT_DIAGNOSTIC_RAW_TEXT_LENGTH = 16_384
private const val MAX_SUPPORT_DIAGNOSTIC_FIELD_VALUE_LENGTH = 512
private const val MAX_SUPPORT_DIAGNOSTIC_CODE_LENGTH = 96
private const val MAX_SUPPORT_DIAGNOSTIC_EXCEPTION_FRAMES = 16
private const val MAX_SUPPORT_DIAGNOSTIC_CAUSE_DEPTH = 4
private const val MAX_SUPPORT_DIAGNOSTIC_CLASS_LENGTH = 180
private const val MAX_SUPPORT_DIAGNOSTIC_METHOD_LENGTH = 120
private const val MAX_SUPPORT_DIAGNOSTIC_FILE_NAME_LENGTH = 120
internal const val SUPPORT_DIAGNOSTIC_ALIAS_LENGTH = 16

private val SUPPORT_DIAGNOSTIC_FIELD_NAME = Regex("^[a-z][a-z0-9_.-]{0,63}$")
private val SUPPORT_DIAGNOSTIC_OPERATION = Regex("^[a-z][a-z0-9._-]{0,79}$")
private val SUPPORT_DIAGNOSTIC_CODE = Regex("^[A-Za-z0-9._:-]{1,96}$")
private val SUPPORT_DIAGNOSTIC_ALIAS = Regex("^<[a-z-]+:[a-f0-9]{16}>$")
private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]")
private val WHITESPACE = Regex("\\s+")
private val SENSITIVE_HEADER_LINE = Regex(
    "(?im)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*",
)
private val AUTHORIZATION_VALUE = Regex(
    "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|password|passphrase|app[-_ ]?password|token|secret)\\s*[:=]\\s*(?:(?:bearer|basic)\\s+)?[^\\s,;]+",
)
private val BEARER_OR_BASIC_VALUE = Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/=_-]{8,}")
private val URL_VALUE = Regex("(?i)\\b(?:https?|dav|webdav)://[^\\s\"'<>]+")
private val EMAIL_VALUE = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
private val WINDOWS_PATH_VALUE = Regex("(?i)(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\r\\n\"<>|,;)]{2,}")
private val UNIX_PATH_VALUE = Regex("(?<![A-Za-z0-9])/(?:[^\\s/:]+/)+[^\\s,;)]*")
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
