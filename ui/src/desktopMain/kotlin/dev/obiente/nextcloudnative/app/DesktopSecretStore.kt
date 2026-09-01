package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.prefs.Preferences

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

internal class DesktopSecretStoreUnavailableException(
    message: String,
    val reason: DesktopSecretStoreUnavailableReason = DesktopSecretStoreUnavailableReason.StorageLockedOrUnavailable,
    cause: Throwable? = null,
) : NextcloudSessionStorageUnavailableException(message, cause)

internal class DesktopSecretDeletionRecoveryUnavailableException(
    cause: Throwable,
) : NextcloudSessionStorageUnavailableException(
    "Secure credential cleanup could not be scheduled for retry.",
    cause,
)

internal class DesktopSecretLegacyCleanupUnavailableException(
    cause: Throwable,
) : NextcloudSessionStorageUnavailableException(
    "The legacy secure credential could not be cleared safely.",
    cause,
)

internal enum class DesktopSecretStoreUnavailableReason {
    StorageLockedOrUnavailable,
    ProviderMissing,
}

internal enum class DesktopSecretStoreKind {
    MacOsKeychain,
    SecretService,
    WindowsCredentialManager,
}

internal fun desktopSecretStoreKind(osName: String = System.getProperty("os.name", "")): DesktopSecretStoreKind =
    when {
        osName.startsWith("Windows", ignoreCase = true) -> DesktopSecretStoreKind.WindowsCredentialManager
        osName.startsWith("Mac", ignoreCase = true) -> DesktopSecretStoreKind.MacOsKeychain
        else -> DesktopSecretStoreKind.SecretService
    }

internal fun defaultDesktopSecretStore(
    osName: String = System.getProperty("os.name", ""),
): DesktopSecretStore = when (desktopSecretStoreKind(osName)) {
    DesktopSecretStoreKind.MacOsKeychain -> MigratingDesktopSecretStore(
        primary = MacOsKeychainSecretStore(),
        legacy = SecretToolDesktopSecretStore(),
        adoption = PreferencesDesktopSecretStoreAdoption(),
    )
    DesktopSecretStoreKind.SecretService -> SecretToolDesktopSecretStore()
    DesktopSecretStoreKind.WindowsCredentialManager -> WindowsCredentialManagerSecretStore()
}

internal interface DesktopSecretStoreAdoption {
    fun state(reference: DesktopSecretReference): DesktopSecretStoreAdoptionState

    fun markAdopted(reference: DesktopSecretReference)

    fun markLegacyCleanupComplete(reference: DesktopSecretReference)
}

internal enum class DesktopSecretStoreAdoptionState {
    NotAdopted,
    AdoptedPendingLegacyCleanup,
    AdoptedAndClean,
}

internal class MigratingDesktopSecretStore(
    private val primary: DesktopSecretStore,
    private val legacy: DesktopSecretStore,
    private val adoption: DesktopSecretStoreAdoption,
) : DesktopSecretStore {
    override fun load(reference: DesktopSecretReference): ByteArray? {
        primary.load(reference)?.let { secret ->
            adoptAndRetryLegacyCleanupBestEffort(reference)
            return secret
        }
        if (adoption.state(reference) != DesktopSecretStoreAdoptionState.NotAdopted) {
            retryLegacyCleanup(reference)
            return null
        }
        val secret = try {
            legacy.load(reference)
        } catch (failure: DesktopSecretStoreUnavailableException) {
            if (failure.reason == DesktopSecretStoreUnavailableReason.ProviderMissing) {
                throw NextcloudSessionLegacyMigrationUnavailableException(failure)
            }
            throw failure
        } ?: return null
        primary.save(reference, username = null, secret = secret)
        adoptAndRetryLegacyCleanupBestEffort(reference)
        return secret
    }

    override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
        primary.save(reference, username, secret)
        adoptAndRetryLegacyCleanupBestEffort(reference)
    }

    override fun clear(reference: DesktopSecretReference) {
        markAdopted(reference)
        val primaryFailure = try {
            primary.clear(reference)
            null
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            failure
        }
        retryLegacyCleanup(reference)?.let { failure ->
            primaryFailure?.let(failure::addSuppressed)
            throw DesktopSecretLegacyCleanupUnavailableException(failure)
        }
        primaryFailure?.let { throw it }
    }

    private fun adoptAndRetryLegacyCleanupBestEffort(reference: DesktopSecretReference) {
        val adoptionDurable = markAdopted(reference)
        val legacyCleanupFailure = retryLegacyCleanup(reference)
        if (!adoptionDurable && legacyCleanupFailure != null) {
            throw DesktopSecretStoreUnavailableException(
                "Keychain adoption and legacy credential cleanup are both unavailable.",
                cause = legacyCleanupFailure,
            )
        }
    }

    private fun markAdopted(reference: DesktopSecretReference): Boolean =
        try {
            if (adoption.state(reference) == DesktopSecretStoreAdoptionState.NotAdopted) {
                adoption.markAdopted(reference)
            }
            true
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }

    private fun retryLegacyCleanup(reference: DesktopSecretReference): Exception? {
        val alreadyClean = try {
            adoption.state(reference) == DesktopSecretStoreAdoptionState.AdoptedAndClean
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (alreadyClean) return null
        try {
            legacy.clear(reference)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            return failure
        }
        try {
            adoption.markLegacyCleanupComplete(reference)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Legacy cleanup is already complete; only the optional durable marker is unavailable.
        }
        return null
    }
}

private class PreferencesDesktopSecretStoreAdoption(
    private val preferences: Preferences = Preferences.userRoot()
        .node("dev/obiente/nextcloudnative/secret-store-adoption-v1"),
) : DesktopSecretStoreAdoption {
    override fun state(reference: DesktopSecretReference): DesktopSecretStoreAdoptionState =
        when (preferences.get(reference.adoptionKey(), null)) {
            ADOPTED_AND_CLEAN -> DesktopSecretStoreAdoptionState.AdoptedAndClean
            ADOPTED_PENDING_CLEANUP, LEGACY_ADOPTED_VALUE ->
                DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup
            else -> DesktopSecretStoreAdoptionState.NotAdopted
        }

    override fun markAdopted(reference: DesktopSecretReference) {
        preferences.put(reference.adoptionKey(), ADOPTED_PENDING_CLEANUP)
        preferences.flush()
    }

    override fun markLegacyCleanupComplete(reference: DesktopSecretReference) {
        check(state(reference) != DesktopSecretStoreAdoptionState.NotAdopted)
        preferences.put(reference.adoptionKey(), ADOPTED_AND_CLEAN)
        preferences.flush()
    }

    private fun DesktopSecretReference.adoptionKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(targetName.encodeToByteArray())
        .toHexString()

    private companion object {
        const val LEGACY_ADOPTED_VALUE = "true"
        const val ADOPTED_PENDING_CLEANUP = "adopted-pending-legacy-cleanup"
        const val ADOPTED_AND_CLEAN = "adopted-and-clean"
    }
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
        }.getOrElse { failure ->
            throw DesktopSecretStoreUnavailableException(
                MISSING_SECRET_TOOL_MESSAGE,
                DesktopSecretStoreUnavailableReason.ProviderMissing,
                failure,
            )
        }
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
            if (process.exitValue() != 0) {
                if (!hasMatchingSecret(reference)) return null
                throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
            }
            if (bytes.isEmpty()) return null
            check(bytes.size <= MAX_SECRET_BYTES) { "The desktop secret service returned an oversized value." }
            return bytes.trimSingleTrailingLineBreak()
        } catch (failure: TimeoutException) {
            timedOut = true
            runCatching {
                process.descendants().forEach { child -> runCatching { child.destroyForcibly() } }
            }
            process.destroyForcibly()
            output.cancel(true)
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE, cause = failure)
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
        val process = runCatching { startProcess(command) }.getOrElse { failure ->
            throw DesktopSecretStoreUnavailableException(
                MISSING_SECRET_TOOL_MESSAGE,
                DesktopSecretStoreUnavailableReason.ProviderMissing,
                failure,
            )
        }
        runCatching {
            process.outputStream.use { it.write(secret) }
        }.getOrElse { failure ->
            process.destroyForcibly()
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE, cause = failure)
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
        }
        if (process.exitValue() != 0) {
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
        }
    }

    private fun hasMatchingSecret(reference: DesktopSecretReference): Boolean {
        val command = buildList {
            add("secret-tool")
            add("search")
            add("--all")
            add("--unlock")
            reference.attributes.forEach { (key, value) ->
                add(key)
                add(value)
            }
        }
        val process = runCatching { startProcess(command) }.getOrElse { failure ->
            throw DesktopSecretStoreUnavailableException(
                MISSING_SECRET_TOOL_MESSAGE,
                DesktopSecretStoreUnavailableReason.ProviderMissing,
                failure,
            )
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nextcloud-native-secret-search").apply { isDaemon = true }
        }
        val output = executor.submit<ByteArray> {
            process.inputStream.use { it.readNBytes(MAX_SECRET_SEARCH_BYTES + 1) }
        }
        var timedOut = false
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw TimeoutException()
            val bytes = output.get(timeoutMillis, TimeUnit.MILLISECONDS)
            if (process.exitValue() != 0 || bytes.size > MAX_SECRET_SEARCH_BYTES) {
                throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
            }
            return bytes.isNotEmpty()
        } catch (failure: TimeoutException) {
            timedOut = true
            process.destroyForcibly()
            output.cancel(true)
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE, cause = failure)
        } finally {
            if (!timedOut) runCatching { process.inputStream.close() }
            executor.shutdownNow()
        }
    }

    override fun clear(reference: DesktopSecretReference) {
        val process = runCatching {
            startProcess(secretToolCommand("clear", reference))
        }.getOrElse { failure ->
            throw DesktopSecretStoreUnavailableException(
                MISSING_SECRET_TOOL_MESSAGE,
                DesktopSecretStoreUnavailableReason.ProviderMissing,
                failure,
            )
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
        }
        if (process.exitValue() != 0) {
            if (!hasMatchingSecret(reference)) return
            throw DesktopSecretStoreUnavailableException(KEYRING_UNAVAILABLE_MESSAGE)
        }
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

internal class MacOsKeychainSecretStore(
    private val api: MacOsKeychainApi = MacOsKeychainApiHolder.instance,
    private val releaseItem: (Pointer) -> Unit = MacOsCoreFoundationApiHolder::release,
    private val deletionRecovery: MacOsKeychainDeletionRecovery = PreferencesMacOsKeychainDeletionRecovery(),
) : DesktopSecretStore {
    private val deletionCoordinator = MacOsKeychainDeletionCoordinator(deletionRecovery, ::deleteTarget)

    init {
        deletionCoordinator.retryAllBestEffort()
    }

    override fun load(reference: DesktopSecretReference): ByteArray? {
        deletionCoordinator.retry(reference.targetName)
        val secretLength = IntByReference()
        val secretData = PointerByReference()
        val item = PointerByReference()
        val identity = reference.macOsIdentity()
        val status = api.SecKeychainFindGenericPassword(
            null,
            identity.service.size,
            identity.service,
            identity.account.size,
            identity.account,
            secretLength,
            secretData,
            item,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return null
        checkMacOsKeychainStatus(status, "load")
        val size = secretLength.value
        val data = secretData.value
        val itemPointer = item.value
        try {
            if (size !in 1..MAX_SECRET_BYTES || data == null) {
                clear(reference)
                return null
            }
            return data.getByteArray(0, size)
        } finally {
            if (data != null) api.SecKeychainItemFreeContent(null, data)
            if (itemPointer != null) releaseItem(itemPointer)
        }
    }

    override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
        require(secret.isNotEmpty() && secret.size <= MAX_SECRET_BYTES)
        deletionCoordinator.retry(reference.targetName)
        val identity = reference.macOsIdentity()
        val item = PointerByReference()
        val findStatus = api.SecKeychainFindGenericPassword(
            null,
            identity.service.size,
            identity.service,
            identity.account.size,
            identity.account,
            null,
            null,
            item,
        )
        when (findStatus) {
            ERR_SEC_ITEM_NOT_FOUND -> add(identity, secret)
            ERR_SEC_SUCCESS -> update(checkNotNull(item.value), secret)
            else -> checkMacOsKeychainStatus(findStatus, "find before save")
        }
    }

    override fun clear(reference: DesktopSecretReference) {
        deletionCoordinator.clear(reference.targetName)
    }

    private fun deleteTarget(targetName: String) {
        val identity = targetName.macOsIdentity()
        val item = PointerByReference()
        val status = api.SecKeychainFindGenericPassword(
            null,
            identity.service.size,
            identity.service,
            identity.account.size,
            identity.account,
            null,
            null,
            item,
        )
        if (status == ERR_SEC_ITEM_NOT_FOUND) return
        checkMacOsKeychainStatus(status, "find before clear")
        val itemPointer = checkNotNull(item.value) { "macOS Keychain returned an empty item." }
        try {
            checkMacOsKeychainStatus(api.SecKeychainItemDelete(itemPointer), "clear")
        } finally {
            releaseItem(itemPointer)
        }
    }

    private fun add(identity: MacOsKeychainIdentity, secret: ByteArray) {
        val status = api.SecKeychainAddGenericPassword(
            null,
            identity.service.size,
            identity.service,
            identity.account.size,
            identity.account,
            secret.size,
            secret,
            null,
        )
        if (status != ERR_SEC_DUPLICATE_ITEM) {
            checkMacOsKeychainStatus(status, "save")
            return
        }
        val item = PointerByReference()
        checkMacOsKeychainStatus(
            api.SecKeychainFindGenericPassword(
                null,
                identity.service.size,
                identity.service,
                identity.account.size,
                identity.account,
                null,
                null,
                item,
            ),
            "find after concurrent save",
        )
        update(checkNotNull(item.value), secret)
    }

    private fun update(item: Pointer, secret: ByteArray) {
        try {
            checkMacOsKeychainStatus(
                api.SecKeychainItemModifyAttributesAndData(item, null, secret.size, secret),
                "update",
            )
        } finally {
            releaseItem(item)
        }
    }
}

internal interface MacOsKeychainApi : com.sun.jna.Library {
    fun SecKeychainFindGenericPassword(
        keychainOrArray: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        secretLength: IntByReference?,
        secretData: PointerByReference?,
        itemRef: PointerByReference,
    ): Int

    fun SecKeychainAddGenericPassword(
        keychain: Pointer?,
        serviceNameLength: Int,
        serviceName: ByteArray,
        accountNameLength: Int,
        accountName: ByteArray,
        secretLength: Int,
        secretData: ByteArray,
        itemRef: PointerByReference?,
    ): Int

    fun SecKeychainItemModifyAttributesAndData(
        itemRef: Pointer,
        attributes: Pointer?,
        secretLength: Int,
        secretData: ByteArray,
    ): Int

    fun SecKeychainItemDelete(itemRef: Pointer): Int

    fun SecKeychainItemFreeContent(attributes: Pointer?, secretData: Pointer?): Int
}

private data class MacOsKeychainIdentity(
    val service: ByteArray,
    val account: ByteArray,
)

private fun DesktopSecretReference.macOsIdentity(): MacOsKeychainIdentity = targetName.macOsIdentity()

private fun String.macOsIdentity(): MacOsKeychainIdentity = MacOsKeychainIdentity(
    service = encodeToByteArray(),
    account = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .toHexString()
        .encodeToByteArray(),
)

private fun checkMacOsKeychainStatus(status: Int, operation: String) {
    if (status == ERR_SEC_SUCCESS) return
    val reason = when (status) {
        ERR_SEC_AUTH_FAILED -> "Keychain access was denied."
        ERR_SEC_INTERACTION_NOT_ALLOWED -> "The login Keychain is locked or unavailable."
        else -> "macOS Keychain failed to $operation the desktop secret (error $status)."
    }
    throw DesktopSecretStoreUnavailableException(reason)
}

private object MacOsKeychainApiHolder {
    val instance: MacOsKeychainApi by lazy {
        Native.load(MACOS_SECURITY_FRAMEWORK, MacOsKeychainApi::class.java)
    }
}

private object MacOsCoreFoundationApiHolder {
    private val api: MacOsCoreFoundationApi by lazy {
        Native.load(MACOS_CORE_FOUNDATION_FRAMEWORK, MacOsCoreFoundationApi::class.java)
    }

    fun release(pointer: Pointer) = api.CFRelease(pointer)
}

private interface MacOsCoreFoundationApi : com.sun.jna.Library {
    fun CFRelease(pointer: Pointer)
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
private const val ERR_SEC_SUCCESS = 0
private const val ERR_SEC_AUTH_FAILED = -25_293
private const val ERR_SEC_DUPLICATE_ITEM = -25_299
private const val ERR_SEC_ITEM_NOT_FOUND = -25_300
private const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25_308
private const val MACOS_SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"
private const val MACOS_CORE_FOUNDATION_FRAMEWORK =
    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val MAX_SECRET_BYTES = 2_560
private const val MAX_SECRET_SEARCH_BYTES = 256 * 1024
private const val MISSING_SECRET_TOOL_MESSAGE =
    "Secure credential storage is unavailable. Install libsecret-tools on Debian or Ubuntu, or libsecret on Fedora or RHEL, then restart Nextcloud Native."
private const val KEYRING_UNAVAILABLE_MESSAGE =
    "Secure credential storage is unavailable. Make sure your desktop keyring is running and unlocked, then try again."
