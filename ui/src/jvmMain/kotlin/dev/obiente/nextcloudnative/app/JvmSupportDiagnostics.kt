package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JvmSupportDiagnostics(
    root: File,
    private val environment: SupportDiagnosticsEnvironment,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    },
) {
    private val root = root.absoluteFile.normalize()
    private val lock = Any()
    private val historyFile = File(root, SUPPORT_DIAGNOSTIC_HISTORY_FILE)
    private val redactionKeyFile = File(root, SUPPORT_DIAGNOSTIC_REDACTION_KEY_FILE)
    private val redactionKey: ByteArray
    private val sanitizer: SupportDiagnosticSanitizer
    private val events = ArrayDeque<SupportDiagnosticEvent>()
    private val revision = MutableStateFlow(0L)
    private var nextSequence = 1L
    private var storedEventBytes = 0L
    private var discardedHistoryBytes = 0L
    private var activeAccountScope: String? = null
    private var storageAvailable = false

    init {
        val initialized = runCatching {
            require(this.root.isDirectory || this.root.mkdirs()) {
                "Could not create the private diagnostics directory."
            }
            removeOrphanedDiagnosticTemporaryFiles()
            loadOrCreateRedactionKey()
        }
        redactionKey = initialized.getOrElse { randomBytes(REDACTION_KEY_BYTES) }
        sanitizer = SupportDiagnosticSanitizer(::keyedAlias)
        storageAvailable = initialized.isSuccess
        if (storageAvailable) {
            runCatching { loadHistory() }
                .onFailure { storageAvailable = false }
        }
    }

    fun registerPrivateValue(value: String?) {
        synchronized(lock) { sanitizer.registerPrivateValue(value) }
    }

    fun setActiveAccount(serverUrl: String?, loginName: String?) {
        synchronized(lock) {
            val nextScope = if (serverUrl.isNullOrBlank() || loginName.isNullOrBlank()) {
                null
            } else {
                sanitizer.registerPrivateValue(serverUrl)
                sanitizer.registerPrivateValue(loginName)
                val identity = "${serverUrl.length}:$serverUrl${loginName.length}:$loginName"
                accountScope(identity)
            }
            updateActiveAccountScope(nextScope)
        }
    }

    fun setActiveAccountIdentity(accountIdentity: String?) {
        synchronized(lock) {
            updateActiveAccountScope(accountIdentity?.takeIf(String::isNotBlank)?.let(::accountScope))
        }
    }

    fun revisions(): StateFlow<Long> = revision.asStateFlow()

    fun isStorageAvailable(): Boolean = synchronized(lock) { storageAvailable }

    fun record(draft: SupportDiagnosticEventDraft) = recordWithScope(draft) { activeAccountScope }

    fun recordForAccountIdentity(accountIdentity: String?, draft: SupportDiagnosticEventDraft) =
        recordWithScope(draft) {
            accountIdentity?.takeIf(String::isNotBlank)?.let(::accountScope)
        }

    private fun recordWithScope(
        draft: SupportDiagnosticEventDraft,
        scope: () -> String?,
    ) {
        if (!storageAvailable) return
        synchronized(lock) {
            runCatching {
                val event = sanitizer.sanitize(
                    sequence = nextSequence++,
                    occurredAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
                    accountScope = scope(),
                    draft = draft,
                )
                val encodedLine = SUPPORT_JSON.encodeToString(event).encodeToByteArray()
                events.addLast(event)
                storedEventBytes += encodedLine.size.toLong() + 1L
                discardedHistoryBytes += pruneEvents(event.occurredAtEpochMillis)
                if (
                    historyFile.isFile &&
                    !shouldCompactSupportDiagnosticHistory(
                        discardedBytes = discardedHistoryBytes,
                        physicalBytes = historyFile.length(),
                        appendedBytes = encodedLine.size.toLong() + 1L,
                    )
                ) {
                    appendHistoryLine(encodedLine)
                } else {
                    persistHistory()
                }
                publishRevision()
            }.onFailure {
                storageAvailable = false
                publishRevision()
            }
        }
    }

    fun summary(): SupportDiagnosticsSummary = synchronized(lock) {
        if (storageAvailable) {
            val expiredBytes = pruneEvents(nowEpochMillis().coerceAtLeast(0L))
            discardedHistoryBytes += expiredBytes
            if (expiredBytes > 0L) {
                runCatching(::persistHistory).onFailure {
                    storageAvailable = false
                    publishRevision()
                }
            }
        }
        val snapshot = visibleEvents()
        SupportDiagnosticsSummary(
            available = storageAvailable,
            eventCount = snapshot.size,
            warningCount = snapshot.count { it.severity == SupportDiagnosticSeverity.Warning },
            errorCount = snapshot.count { it.severity == SupportDiagnosticSeverity.Error },
            oldestEventAtEpochMillis = snapshot.firstOrNull()?.occurredAtEpochMillis,
            newestEventAtEpochMillis = snapshot.lastOrNull()?.occurredAtEpochMillis,
            components = snapshot.mapTo(linkedSetOf(), SupportDiagnosticEvent::component),
            storedBytes = snapshot.sumOf(::encodedEventBytes),
            includedFiles = SUPPORT_BUNDLE_INCLUDED_FILES,
            recentEvents = snapshot.takeLast(MAX_SUPPORT_DIAGNOSTIC_PREVIEW_EVENTS).map { event ->
                SupportDiagnosticPreviewEvent(
                    occurredAtEpochMillis = event.occurredAtEpochMillis,
                    severity = event.severity,
                    component = event.component,
                    operation = event.operation,
                    outcome = event.outcome,
                    code = event.code,
                )
            },
            explanation = if (storageAvailable) {
                null
            } else {
                "Private diagnostic storage is unavailable on this device."
            },
        )
    }

    fun clear(): Boolean = synchronized(lock) {
        if (!storageAvailable) return@synchronized false
        runCatching {
            events.clear()
            storedEventBytes = 0L
            persistHistory()
            publishRevision()
            true
        }.getOrElse {
            storageAvailable = false
            false
        }
    }

    fun writeBundle(
        destination: File,
        reproductionSteps: String,
        featureState: List<SupportDiagnosticFieldDraft>,
    ): File = synchronized(lock) {
        check(storageAvailable) { "Private diagnostic storage is unavailable." }
        require(featureState.size <= MAX_SUPPORT_DIAGNOSTIC_FIELDS)
        val createdAt = nowEpochMillis().coerceAtLeast(0L)
        discardedHistoryBytes += pruneEvents(createdAt)
        if (discardedHistoryBytes > 0L) persistHistory()
        val snapshot = visibleEvents()
        val report = SupportBundleReport(
            createdAtEpochMillis = createdAt,
            environment = environment.safeForReport(),
            reproductionSteps = sanitizer.sanitizeUserDescription(reproductionSteps).takeIf(String::isNotBlank),
            eventCount = snapshot.size,
            warningCount = snapshot.count { it.severity == SupportDiagnosticSeverity.Warning },
            errorCount = snapshot.count { it.severity == SupportDiagnosticSeverity.Error },
            components = snapshot.map { it.component }.distinct().sortedBy(Enum<*>::name),
            featureState = sanitizer.sanitizeFields(featureState),
        )
        val reportBytes = SUPPORT_JSON.encodeToString(report).encodeToByteArray()
        val eventBytes = snapshot.joinToString(separator = "\n", postfix = if (snapshot.isEmpty()) "" else "\n") {
            SUPPORT_JSON.encodeToString(it)
        }.encodeToByteArray()
        val readmeBytes = supportBundleReadme().encodeToByteArray()
        val content = linkedMapOf(
            "README.txt" to readmeBytes,
            "report.json" to reportBytes,
            "events.jsonl" to eventBytes,
        )
        val manifest = SupportBundleManifest(
            createdAtEpochMillis = createdAt,
            entries = content.map { (name, bytes) ->
                SupportBundleManifestEntry(
                    name = name,
                    bytes = bytes.size.toLong(),
                    sha256 = bytes.sha256Hex(),
                )
            },
        )
        val completeContent = content + (
            "manifest.json" to SUPPORT_JSON.encodeToString(manifest).encodeToByteArray()
            )
        require(completeContent.values.sumOf { it.size.toLong() } <= MAX_SUPPORT_BUNDLE_UNCOMPRESSED_BYTES) {
            "The bounded diagnostic report is unexpectedly large."
        }
        writeZipAtomically(destination, completeContent, createdAt)
        destination
    }

    private fun loadHistory() {
        if (!historyFile.isFile) return
        if (historyFile.length() > MAX_SUPPORT_DIAGNOSTIC_HISTORY_READ_BYTES) {
            persistHistory()
            return
        }
        val loaded: List<SupportDiagnosticEvent> = historyFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                if (line.length > MAX_SUPPORT_DIAGNOSTIC_EVENT_LINE_LENGTH) return@mapNotNull null
                runCatching { SUPPORT_JSON.decodeFromString<SupportDiagnosticEvent>(line) }.getOrNull()
            }
                .toList()
                .sortedBy(SupportDiagnosticEvent::sequence)
                .takeLast(MAX_SUPPORT_DIAGNOSTIC_EVENTS)
                .mapIndexed { index, event -> event.copy(sequence = index.toLong() + 1L) }
        }
        events.addAll(loaded)
        storedEventBytes = loaded.sumOf(::encodedEventBytes)
        nextSequence = loaded.size.toLong() + 1L
        discardedHistoryBytes += pruneEvents(nowEpochMillis().coerceAtLeast(0L))
        persistHistory()
    }

    private fun removeOrphanedDiagnosticTemporaryFiles() {
        Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.forEach { path ->
                val name = path.fileName.toString()
                val recognized = name.endsWith(".tmp") && (
                    name.startsWith(".$SUPPORT_DIAGNOSTIC_HISTORY_FILE.") ||
                        name.startsWith(".$SUPPORT_DIAGNOSTIC_REDACTION_KEY_FILE.")
                )
                if (recognized && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun pruneEvents(now: Long): Long {
        var removedBytes = 0L
        val cutoff = (now - MAX_SUPPORT_DIAGNOSTIC_AGE_MILLIS).coerceAtLeast(0L)
        val iterator = events.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next()
            if (event.occurredAtEpochMillis < cutoff) {
                val eventBytes = encodedEventBytes(event)
                iterator.remove()
                storedEventBytes -= eventBytes
                removedBytes += eventBytes
            }
        }
        while (events.size > MAX_SUPPORT_DIAGNOSTIC_EVENTS) {
            val eventBytes = encodedEventBytes(events.removeFirst())
            storedEventBytes -= eventBytes
            removedBytes += eventBytes
        }
        while (events.isNotEmpty() && storedEventBytes > MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES) {
            val eventBytes = encodedEventBytes(events.removeFirst())
            storedEventBytes -= eventBytes
            removedBytes += eventBytes
        }
        return removedBytes
    }

    private fun persistHistory() {
        require(root.isDirectory)
        val bytes = events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") {
            SUPPORT_JSON.encodeToString(it)
        }.encodeToByteArray()
        require(bytes.size.toLong() <= MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES)
        writeFileAtomically(historyFile, bytes)
        storedEventBytes = bytes.size.toLong()
        discardedHistoryBytes = 0L
    }

    private fun appendHistoryLine(encodedLine: ByteArray) {
        require(storedEventBytes <= MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES)
        require(historyFile.length() + encodedLine.size.toLong() + 1L <= MAX_SUPPORT_DIAGNOSTIC_PHYSICAL_HISTORY_BYTES)
        FileOutputStream(historyFile, true).use { output ->
            output.write(encodedLine)
            output.write('\n'.code)
            output.fd.sync()
        }
        require(historyFile.length() <= MAX_SUPPORT_DIAGNOSTIC_PHYSICAL_HISTORY_BYTES)
    }

    private fun visibleEvents(): List<SupportDiagnosticEvent> = events.filter { event ->
        event.accountScope == null || event.accountScope == activeAccountScope
    }

    private fun accountScope(identity: String): String =
        "<account:${keyedAlias("account\u0000$identity").take(SUPPORT_DIAGNOSTIC_ALIAS_LENGTH)}>"

    private fun updateActiveAccountScope(nextScope: String?) {
        if (activeAccountScope != nextScope) {
            activeAccountScope = nextScope
            publishRevision()
        }
    }

    private fun publishRevision() {
        revision.value = if (revision.value == Long.MAX_VALUE) 0L else revision.value + 1L
    }

    private fun encodedEventBytes(event: SupportDiagnosticEvent): Long =
        SUPPORT_JSON.encodeToString(event).encodeToByteArray().size.toLong() + 1L

    private fun loadOrCreateRedactionKey(): ByteArray {
        if (redactionKeyFile.isFile) {
            val encoded = redactionKeyFile.readText(Charsets.US_ASCII).trim()
            val decoded = Base64.getDecoder().decode(encoded)
            require(decoded.size == REDACTION_KEY_BYTES) { "The diagnostic redaction key is invalid." }
            return decoded
        }
        val key = randomBytes(REDACTION_KEY_BYTES)
        require(key.size == REDACTION_KEY_BYTES)
        writeFileAtomically(redactionKeyFile, Base64.getEncoder().encode(key))
        runCatching {
            Files.setPosixFilePermissions(
                redactionKeyFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        return key
    }

    private fun keyedAlias(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(redactionKey, "HmacSHA256"))
        return mac.doFinal(value.encodeToByteArray()).toHex()
    }
}

fun Throwable.toSupportDiagnosticExceptionDraft(
    depth: Int = 0,
): SupportDiagnosticExceptionDraft = SupportDiagnosticExceptionDraft(
    type = javaClass.name,
    message = message,
    frames = stackTrace.take(MAX_JVM_SUPPORT_EXCEPTION_FRAMES).map { frame ->
        SupportDiagnosticFrame(
            declaringClass = frame.className,
            methodName = frame.methodName,
            fileName = frame.fileName,
            lineNumber = frame.lineNumber.takeIf { it >= 0 },
        )
    },
    cause = cause
        ?.takeIf { it !== this && depth + 1 < MAX_JVM_SUPPORT_CAUSE_DEPTH }
        ?.toSupportDiagnosticExceptionDraft(depth + 1),
)

private fun SupportDiagnosticsEnvironment.safeForReport(): SupportDiagnosticsEnvironment =
    SupportDiagnosticsEnvironment(
        appVersion = appVersion.safeEnvironmentValue(),
        packageVersion = packageVersion.safeEnvironmentValue(),
        platform = platform.safeEnvironmentValue(),
        operatingSystemVersion = operatingSystemVersion.safeEnvironmentValue(),
        architecture = architecture.safeEnvironmentValue(),
    )

private fun String.safeEnvironmentValue(): String =
    filter { character -> character.isLetterOrDigit() || character in setOf(' ', '.', '_', '-', '(', ')') }
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Unknown" }
        .take(MAX_SUPPORT_ENVIRONMENT_VALUE_LENGTH)

private fun writeFileAtomically(destination: File, bytes: ByteArray) {
    val parent = requireNotNull(destination.parentFile)
    require(parent.isDirectory || parent.mkdirs())
    val temporary = Files.createTempFile(parent.toPath(), ".${destination.name}.", ".tmp").toFile()
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveAtomically(temporary, destination)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

private fun writeZipAtomically(
    destination: File,
    content: Map<String, ByteArray>,
    createdAtEpochMillis: Long,
) {
    val parent = requireNotNull(destination.absoluteFile.parentFile)
    require(parent.isDirectory || parent.mkdirs()) { "Could not create the diagnostic export directory." }
    val temporary = Files.createTempFile(parent.toPath(), ".${destination.name}.", ".tmp").toFile()
    try {
        FileOutputStream(temporary).use { fileOutput ->
            val zip = ZipOutputStream(fileOutput)
            try {
                content.forEach { (name, bytes) ->
                    require(name in SUPPORT_BUNDLE_INCLUDED_FILES)
                    zip.putNextEntry(ZipEntry(name).apply { time = createdAtEpochMillis })
                    zip.write(bytes)
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
        require(temporary.length() in 1L..MAX_SUPPORT_BUNDLE_ARCHIVE_BYTES) {
            "The diagnostic archive is outside its size bound."
        }
        moveAtomically(temporary, destination)
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

private fun moveAtomically(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun supportBundleReadme(): String = """
    Nextcloud Native anonymized support report

    This archive was created locally after an explicit export request.

    Automatically collected diagnostic values are sanitized before they are written to app-private
    history. They do not include credentials, authorization or cookie values, HTTP bodies, private
    server responses, raw server URLs, usernames, account identifiers, local paths, remote names,
    or user file contents. Free-text error messages are stored only as keyed fingerprints. Related
    private values use keyed aliases so repeated failures can be correlated without revealing the
    original value. The key is not included in this archive.

    Optional reproduction steps are user-supplied. Recognizable secrets, URLs, addresses, and paths
    are anonymized, but you should still review that text before sharing the report.

    Files:
    - report.json: app, platform, feature, and user-supplied reproduction context
    - events.jsonl: bounded structured application events in chronological order
    - manifest.json: byte size and SHA-256 digest for each report payload

    Review the archive before sharing it. Reports are never uploaded automatically.
""".trimIndent() + "\n"

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

@Serializable
private data class SupportBundleReport(
    val schemaVersion: Int = 1,
    val createdAtEpochMillis: Long,
    val environment: SupportDiagnosticsEnvironment,
    val reproductionSteps: String?,
    val eventCount: Int,
    val warningCount: Int,
    val errorCount: Int,
    val components: List<SupportDiagnosticComponent>,
    val featureState: List<SupportDiagnosticField>,
)

@Serializable
private data class SupportBundleManifest(
    val schemaVersion: Int = 1,
    val createdAtEpochMillis: Long,
    val entries: List<SupportBundleManifestEntry>,
)

@Serializable
private data class SupportBundleManifestEntry(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

private val SUPPORT_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
private const val SUPPORT_DIAGNOSTIC_HISTORY_FILE = "events-v1.jsonl"
private const val SUPPORT_DIAGNOSTIC_REDACTION_KEY_FILE = "redaction-key-v1"
private const val REDACTION_KEY_BYTES = 32
private const val SUPPORT_DIAGNOSTIC_COMPACTION_SLACK_BYTES = 256L * 1_024L
private const val MAX_SUPPORT_DIAGNOSTIC_PHYSICAL_HISTORY_BYTES =
    MAX_SUPPORT_DIAGNOSTIC_STORED_BYTES + SUPPORT_DIAGNOSTIC_COMPACTION_SLACK_BYTES
private const val MAX_SUPPORT_DIAGNOSTIC_HISTORY_READ_BYTES = MAX_SUPPORT_DIAGNOSTIC_PHYSICAL_HISTORY_BYTES
private const val MAX_SUPPORT_DIAGNOSTIC_EVENT_LINE_LENGTH = 64 * 1_024
private const val MAX_SUPPORT_BUNDLE_UNCOMPRESSED_BYTES = 3L * 1024L * 1024L
private const val MAX_SUPPORT_BUNDLE_ARCHIVE_BYTES = 4L * 1024L * 1024L
private const val MAX_SUPPORT_ENVIRONMENT_VALUE_LENGTH = 160
private const val MAX_JVM_SUPPORT_EXCEPTION_FRAMES = 32
private const val MAX_JVM_SUPPORT_CAUSE_DEPTH = 6
private const val MAX_SUPPORT_DIAGNOSTIC_PREVIEW_EVENTS = 20

internal fun shouldCompactSupportDiagnosticHistory(
    discardedBytes: Long,
    physicalBytes: Long,
    appendedBytes: Long,
): Boolean {
    require(discardedBytes >= 0L)
    require(physicalBytes >= 0L)
    require(appendedBytes >= 0L)
    return discardedBytes >= SUPPORT_DIAGNOSTIC_COMPACTION_SLACK_BYTES ||
        physicalBytes + appendedBytes > MAX_SUPPORT_DIAGNOSTIC_PHYSICAL_HISTORY_BYTES
}
