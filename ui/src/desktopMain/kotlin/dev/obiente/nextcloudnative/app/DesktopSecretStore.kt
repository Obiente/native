package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class DesktopSecretReference(
    val targetName: String,
    val label: String,
    val attributes: Map<String, String>,
) {
    init {
        require(targetName.isNotBlank() && targetName.length <= MAX_TARGET_NAME_CHARACTERS)
        require(label.isNotBlank() && label.length <= MAX_LABEL_CHARACTERS)
        require(attributes.isNotEmpty())
        require(attributes.all { (key, value) ->
            key.matches(ATTRIBUTE_NAME_PATTERN) &&
                value.isNotBlank() &&
                value.length <= MAX_ATTRIBUTE_VALUE_CHARACTERS
        })
    }

    private companion object {
        const val MAX_TARGET_NAME_CHARACTERS = 512
        const val MAX_LABEL_CHARACTERS = 256
        const val MAX_ATTRIBUTE_VALUE_CHARACTERS = 2_048
        val ATTRIBUTE_NAME_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
    }
}

internal interface DesktopSecretStore {
    fun load(reference: DesktopSecretReference): ByteArray?

    fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray)

    fun clear(reference: DesktopSecretReference)
}

internal enum class DesktopSecretStoreKind {
    SecretService,
    WindowsCredentialManager,
}

internal fun desktopSecretStoreKind(osName: String = System.getProperty("os.name", "")): DesktopSecretStoreKind =
    if (osName.startsWith("Windows", ignoreCase = true)) {
        DesktopSecretStoreKind.WindowsCredentialManager
    } else {
        DesktopSecretStoreKind.SecretService
    }

internal fun defaultDesktopSecretStore(
    osName: String = System.getProperty("os.name", ""),
): DesktopSecretStore = when (desktopSecretStoreKind(osName)) {
    DesktopSecretStoreKind.SecretService -> SecretToolDesktopSecretStore()
    DesktopSecretStoreKind.WindowsCredentialManager -> WindowsCredentialManagerSecretStore()
}

internal fun desktopSessionSecretReference(serverUrl: String, loginName: String): DesktopSecretReference {
    require(serverUrl.isNotBlank() && loginName.isNotBlank())
    val identity = MessageDigest.getInstance("SHA-256")
        .digest("$serverUrl\u0000$loginName".encodeToByteArray())
        .toHexString()
    return DesktopSecretReference(
        targetName = "$WINDOWS_CREDENTIAL_PREFIX/session/$identity",
        label = "Nextcloud Native app password",
        attributes = linkedMapOf(
            "application" to DESKTOP_APPLICATION_ID,
            "server" to serverUrl,
            "login" to loginName,
        ),
    )
}

internal fun desktopDeckDraftSecretReference(): DesktopSecretReference = DesktopSecretReference(
    targetName = "$WINDOWS_CREDENTIAL_PREFIX/deck-card-drafts/v1",
    label = "Nextcloud Native Deck draft encryption",
    attributes = linkedMapOf(
        "application" to DESKTOP_APPLICATION_ID,
        "purpose" to "deck-card-drafts",
        "schema" to "1",
    ),
)

internal class SecretToolDesktopSecretStore(
    private val timeoutMillis: Long = 10_000L,
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    },
) : DesktopSecretStore {
    init {
        require(timeoutMillis > 0L)
    }

    override fun load(reference: DesktopSecretReference): ByteArray? {
        val process = runCatching {
            startProcess(secretToolCommand("lookup", reference))
        }.getOrElse { return null }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nextcloud-native-secret-reader").apply { isDaemon = true }
        }
        val startedAt = System.nanoTime()
        val output = executor.submit<ByteArray> {
            process.inputStream.use { it.readNBytes(MAX_SECRET_BYTES + 1) }
        }
        var timedOut = false
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw TimeoutException()
            }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val remainingMillis = (timeoutMillis - elapsedMillis).coerceAtLeast(1L)
            val bytes = output.get(remainingMillis, TimeUnit.MILLISECONDS)
            if (process.exitValue() != 0 || bytes.isEmpty()) return null
            check(bytes.size <= MAX_SECRET_BYTES) { "The desktop secret service returned an oversized value." }
            return bytes.trimSingleTrailingLineBreak()
        } catch (_: TimeoutException) {
            timedOut = true
            runCatching {
                process.descendants().forEach { child -> runCatching { child.destroyForcibly() } }
            }
            process.destroyForcibly()
            output.cancel(true)
            error("Timed out while loading a desktop secret.")
        } finally {
            if (!timedOut) runCatching { process.inputStream.close() }
            executor.shutdownNow()
        }
    }

    override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES)
        val command = buildList {
            add("secret-tool")
            add("store")
            add("--label=${reference.label}")
            reference.attributes.forEach { (key, value) ->
                add(key)
                add(value)
            }
        }
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.use { it.write(secret) }
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            "Timed out while storing a desktop secret."
        }
        check(process.exitValue() == 0) { "Could not store a secret in the desktop keyring." }
    }

    override fun clear(reference: DesktopSecretReference) {
        val process = runCatching {
            ProcessBuilder(secretToolCommand("clear", reference))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { return }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    private fun secretToolCommand(command: String, reference: DesktopSecretReference): List<String> = buildList {
        add("secret-tool")
        add(command)
        reference.attributes.forEach { (key, value) ->
            add(key)
            add(value)
        }
    }
}

internal class WindowsCredentialManagerSecretStore(
    private val api: WindowsCredentialApi = WindowsCredentialApiHolder.instance,
) : DesktopSecretStore {
    override fun load(reference: DesktopSecretReference): ByteArray? {
        val result = PointerByReference()
        if (!api.CredReadW(WString(reference.targetName), CRED_TYPE_GENERIC, 0, result)) {
            val error = Native.getLastError()
            if (error == ERROR_NOT_FOUND) return null
            error("Windows Credential Manager could not load the desktop secret (error $error).")
        }
        val pointer = result.value ?: error("Windows Credential Manager returned an empty credential.")
        return try {
            val credential = WindowsCredential(pointer).apply { read() }
            val size = credential.credentialBlobSize
            check(size in 1..MAX_SECRET_BYTES) {
                "Windows Credential Manager returned an invalid secret size."
            }
            requireNotNull(credential.credentialBlob).getByteArray(0, size)
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES)
        val memory = Memory(secret.size.toLong())
        try {
            memory.write(0, secret, 0, secret.size)
            val credential = WindowsCredential().apply {
                flags = 0
                type = CRED_TYPE_GENERIC
                targetName = WString(reference.targetName)
                comment = WString(reference.label)
                credentialBlobSize = secret.size
                credentialBlob = memory
                persist = CRED_PERSIST_LOCAL_MACHINE
                attributeCount = 0
                attributes = null
                targetAlias = null
                userName = username?.takeIf(String::isNotBlank)?.let(::WString)
                write()
            }
            if (!api.CredWriteW(credential, 0)) {
                val error = Native.getLastError()
                error("Windows Credential Manager could not store the desktop secret (error $error).")
            }
        } finally {
            memory.clear(secret.size.toLong())
        }
    }

    override fun clear(reference: DesktopSecretReference) {
        if (!api.CredDeleteW(WString(reference.targetName), CRED_TYPE_GENERIC, 0)) {
            val error = Native.getLastError()
            check(error == ERROR_NOT_FOUND) {
                "Windows Credential Manager could not clear the desktop secret (error $error)."
            }
        }
    }
}

internal interface WindowsCredentialApi : StdCallLibrary {
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

    fun CredWriteW(credential: WindowsCredential, flags: Int): Boolean

    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean

    fun CredFree(buffer: Pointer)
}

internal class WindowsCredential(pointer: Pointer? = null) : Structure(pointer) {
    @JvmField var flags: Int = 0
    @JvmField var type: Int = 0
    @JvmField var targetName: WString? = null
    @JvmField var comment: WString? = null
    @JvmField var lastWritten: WindowsFileTime = WindowsFileTime()
    @JvmField var credentialBlobSize: Int = 0
    @JvmField var credentialBlob: Pointer? = null
    @JvmField var persist: Int = 0
    @JvmField var attributeCount: Int = 0
    @JvmField var attributes: Pointer? = null
    @JvmField var targetAlias: WString? = null
    @JvmField var userName: WString? = null

    override fun getFieldOrder(): List<String> = listOf(
        "flags",
        "type",
        "targetName",
        "comment",
        "lastWritten",
        "credentialBlobSize",
        "credentialBlob",
        "persist",
        "attributeCount",
        "attributes",
        "targetAlias",
        "userName",
    )
}

internal class WindowsFileTime : Structure(), Structure.ByValue {
    @JvmField var lowDateTime: Int = 0
    @JvmField var highDateTime: Int = 0

    override fun getFieldOrder(): List<String> = listOf("lowDateTime", "highDateTime")
}

private object WindowsCredentialApiHolder {
    val instance: WindowsCredentialApi by lazy {
        Native.load("Advapi32", WindowsCredentialApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

private fun ByteArray.trimSingleTrailingLineBreak(): ByteArray = when {
    size >= 2 && this[size - 2] == '\r'.code.toByte() && last() == '\n'.code.toByte() -> copyOf(size - 2)
    isNotEmpty() && last() == '\n'.code.toByte() -> copyOf(size - 1)
    else -> this
}

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val DESKTOP_APPLICATION_ID = "dev.obiente.nextcloudnative"
private const val WINDOWS_CREDENTIAL_PREFIX = "Obiente/NextcloudNative"
private const val CRED_TYPE_GENERIC = 1
private const val CRED_PERSIST_LOCAL_MACHINE = 2
private const val ERROR_NOT_FOUND = 1_168
private const val MAX_SECRET_BYTES = 2_560
