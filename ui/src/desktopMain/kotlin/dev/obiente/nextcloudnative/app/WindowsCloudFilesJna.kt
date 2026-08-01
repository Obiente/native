package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** 64-bit Windows CldApi.dll binding kept behind [WindowsCloudFilesApi] for deterministic tests. */
internal class JnaWindowsCloudFilesApi : WindowsCloudFilesApi {
    private val cldApi: CldApi
    private val kernelFiles: KernelFileApi
    private val callbacksByConnection = ConcurrentHashMap<Long, CallbackLifetime>()

    init {
        require(isWindowsDesktop()) { "CldApi.dll is only available on Windows." }
        require(Native.POINTER_SIZE == 8) { "Nextcloud Native Cloud Files requires 64-bit Windows." }
        cldApi = Native.load("CldApi", CldApi::class.java)
        kernelFiles = Native.load("kernel32", KernelFileApi::class.java)
    }

    override fun registerSyncRoot(root: Path, syncRootIdentity: ByteArray) {
        val identity = syncRootIdentity.nativeMemory()
        val registration = CfSyncRegistration().apply {
            structSize = size()
            providerName = WString("Nextcloud Native")
            providerVersion = WString("0.1.0")
            syncRootIdentityPointer = identity
            syncRootIdentityLength = syncRootIdentity.size
            fileIdentity = identity
            fileIdentityLength = syncRootIdentity.size
            providerId = Guid.GUID("{6D456713-7D9A-4A39-90CE-127998DE42D7}")
            write()
        }
        val policies = CfSyncPolicies().apply {
            structSize = size()
            hydration = CfPolicy(CF_HYDRATION_POLICY_PROGRESSIVE, CF_HYDRATION_POLICY_MODIFIER_AUTO_DEHYDRATION_ALLOWED)
            population = CfPolicy(CF_POPULATION_POLICY_FULL, 0)
            inSync = CF_INSYNC_POLICY_TRACK_FILE_CREATION_TIME or
                CF_INSYNC_POLICY_TRACK_FILE_LAST_WRITE_TIME or
                CF_INSYNC_POLICY_TRACK_DIRECTORY_CREATION_TIME or
                CF_INSYNC_POLICY_TRACK_DIRECTORY_LAST_WRITE_TIME
            hardLink = CF_HARDLINK_POLICY_NONE
            placeholderManagement = CF_PLACEHOLDER_MANAGEMENT_POLICY_DEFAULT
            write()
        }
        checkHResult(
            cldApi.CfRegisterSyncRoot(
                WString(root.toAbsolutePath().toString()),
                registration,
                policies,
                CF_REGISTER_FLAG_UPDATE or CF_REGISTER_FLAG_MARK_IN_SYNC_ON_ROOT,
            ),
            "register the Windows Cloud Files root",
        )
        identity.clear()
    }

    override fun unregisterSyncRoot(root: Path) {
        val result = cldApi.CfUnregisterSyncRoot(WString(root.toAbsolutePath().toString()))
        if (result in SYNC_ROOT_ALREADY_UNREGISTERED_RESULTS) return
        checkHResult(result, "unregister the Windows Cloud Files root")
    }

    override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long {
        val types = intArrayOf(
            CF_CALLBACK_TYPE_FETCH_DATA,
            CF_CALLBACK_TYPE_CANCEL_FETCH_DATA,
            CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS,
            CF_CALLBACK_TYPE_CANCEL_FETCH_PLACEHOLDERS,
            CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION,
            CF_CALLBACK_TYPE_NOTIFY_DELETE,
            CF_CALLBACK_TYPE_NOTIFY_RENAME,
            CF_CALLBACK_TYPE_NONE,
        )
        val nativeCallbacks = types.filter { it != CF_CALLBACK_TYPE_NONE }.associateWith { callbackType ->
            CldCallback { infoPointer, parametersPointer ->
                currentCallbackType.set(callbackType)
                try {
                    runCatching { dispatchCallback(callbacks, infoPointer, parametersPointer) }
                } finally {
                    currentCallbackType.remove()
                }
            }
        }
        val registrations = CfCallbackRegistration().toArray(types.size).map { it as CfCallbackRegistration }
        types.forEachIndexed { index, type ->
            registrations[index].type = type
            registrations[index].callback = nativeCallbacks[type]
            registrations[index].write()
        }
        val key = LongByReference()
        checkHResult(
            cldApi.CfConnectSyncRoot(
                WString(root.toAbsolutePath().toString()),
                registrations.first().pointer,
                null,
                CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH,
                key,
            ),
            "connect the Windows Cloud Files provider",
        )
        val value = key.value
        callbacksByConnection[value] = CallbackLifetime(nativeCallbacks.values.toList(), registrations)
        return value
    }

    override fun disconnect(connectionKey: Long) {
        checkHResult(cldApi.CfDisconnectSyncRoot(connectionKey), "disconnect the Windows Cloud Files provider")
        callbacksByConnection.remove(connectionKey)
    }

    override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) {
        if (placeholders.isEmpty()) return
        val native = NativePlaceholderArray(placeholders)
        val processed = IntByReference()
        checkHResult(
            cldApi.CfCreatePlaceholders(
                WString(baseDirectory.toAbsolutePath().toString()),
                native.firstPointer,
                placeholders.size,
                CF_CREATE_FLAG_STOP_ON_ERROR,
                processed,
            ),
            "create Windows Cloud Files placeholders",
        )
        check(processed.value == placeholders.size) { "Windows created only some requested placeholders." }
        native.requireSuccessful()
    }

    override fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray) {
        val buffer = bytes.nativeMemory()
        execute(info, CF_OPERATION_TYPE_TRANSFER_DATA, TRANSFER_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(TRANSFER_FLAGS_OFFSET, 0)
            parameters.setInt(TRANSFER_STATUS_OFFSET, STATUS_SUCCESS)
            parameters.setPointer(TRANSFER_BUFFER_OFFSET, buffer)
            parameters.setLong(TRANSFER_OFFSET_OFFSET, offset)
            parameters.setLong(TRANSFER_LENGTH_OFFSET, bytes.size.toLong())
        }
        buffer.clear()
    }

    override fun failData(
        info: WindowsCloudCallbackInfo,
        offset: Long,
        length: Long,
        message: String,
    ) {
        execute(info, CF_OPERATION_TYPE_TRANSFER_DATA, TRANSFER_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(TRANSFER_FLAGS_OFFSET, 0)
            parameters.setInt(TRANSFER_STATUS_OFFSET, STATUS_CLOUD_FILE_UNSUCCESSFUL)
            parameters.setPointer(TRANSFER_BUFFER_OFFSET, null)
            parameters.setLong(TRANSFER_OFFSET_OFFSET, offset)
            parameters.setLong(TRANSFER_LENGTH_OFFSET, length)
        }
    }

    override fun completePlaceholderFetch(
        info: WindowsCloudCallbackInfo,
        placeholders: List<WindowsCloudPlaceholder>,
    ) {
        val native = NativePlaceholderArray(placeholders)
        execute(info, CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS, PLACEHOLDER_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(PLACEHOLDERS_FLAGS_OFFSET, 0)
            parameters.setInt(PLACEHOLDERS_STATUS_OFFSET, STATUS_SUCCESS)
            parameters.setLong(PLACEHOLDERS_TOTAL_OFFSET, placeholders.size.toLong())
            parameters.setPointer(PLACEHOLDERS_ARRAY_OFFSET, native.firstPointer)
            parameters.setInt(PLACEHOLDERS_COUNT_OFFSET, placeholders.size)
            parameters.setInt(PLACEHOLDERS_PROCESSED_OFFSET, 0)
        }
    }

    override fun failPlaceholderFetch(info: WindowsCloudCallbackInfo) {
        execute(info, CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS, PLACEHOLDER_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(PLACEHOLDERS_FLAGS_OFFSET, 0)
            parameters.setInt(PLACEHOLDERS_STATUS_OFFSET, STATUS_CLOUD_FILE_UNSUCCESSFUL)
            parameters.setLong(PLACEHOLDERS_TOTAL_OFFSET, 0L)
            parameters.setPointer(PLACEHOLDERS_ARRAY_OFFSET, null)
            parameters.setInt(PLACEHOLDERS_COUNT_OFFSET, 0)
            parameters.setInt(PLACEHOLDERS_PROCESSED_OFFSET, 0)
        }
    }

    override fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean) {
        execute(info, CF_OPERATION_TYPE_ACK_DELETE, ACK_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(ACK_FLAGS_OFFSET, 0)
            parameters.setInt(ACK_STATUS_OFFSET, if (accepted) STATUS_SUCCESS else STATUS_CLOUD_FILE_UNSUCCESSFUL)
        }
    }

    override fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean) {
        execute(info, CF_OPERATION_TYPE_ACK_RENAME, ACK_PARAMETERS_SIZE) { parameters ->
            parameters.setInt(ACK_FLAGS_OFFSET, 0)
            parameters.setInt(ACK_STATUS_OFFSET, if (accepted) STATUS_SUCCESS else STATUS_CLOUD_FILE_UNSUCCESSFUL)
        }
    }

    override fun placeholderState(path: Path): WindowsCloudPlaceholderState {
        return withFindData(path, WindowsCloudPlaceholderState.Absent) { findData ->
            val state = cldApi.CfGetPlaceholderStateFromFindData(findData.pointer)
            when {
                state and CF_PLACEHOLDER_STATE_PLACEHOLDER == 0 -> WindowsCloudPlaceholderState.Absent
                state and CF_PLACEHOLDER_STATE_IN_SYNC != 0 -> WindowsCloudPlaceholderState.InSync
                else -> WindowsCloudPlaceholderState.Dirty
            }
        }
    }

    override fun allocatedBytes(path: Path): Long {
        if (!Files.isRegularFile(path)) return 0L
        val high = IntByReference()
        val low = kernelFiles.GetCompressedFileSizeW(WString(path.toAbsolutePath().toString()), high)
        if (low == -1 && Native.getLastError() != 0) return 0L
        return ((high.value.toLong() and 0xffff_ffffL) shl 32) or (low.toLong() and 0xffff_ffffL)
    }

    override fun lastAccessedAtEpochMillis(path: Path): Long =
        withFindData(path, 0L) { findData -> findData.ftLastAccessTime.toTime().coerceAtLeast(0L) }

    override fun isPinned(path: Path): Boolean = withFindData(path, false) { findData ->
        findData.dwFileAttributes and FILE_ATTRIBUTE_PINNED != 0
    }

    override fun placeholderIdentity(path: Path): ByteArray? {
        if (!Files.exists(path)) return null
        return runCatching {
            withFileHandle(path, write = false) { handle ->
                val buffer = Memory(CF_STANDARD_INFO_BUFFER_BYTES.toLong()).apply { clear() }
                val returned = IntByReference()
                checkHResult(
                    cldApi.CfGetPlaceholderInfo(
                        handle,
                        CF_PLACEHOLDER_INFO_STANDARD,
                        buffer,
                        CF_STANDARD_INFO_BUFFER_BYTES,
                        returned,
                    ),
                    "read a Windows Cloud Files placeholder identity",
                )
                val identityLength = buffer.getInt(CF_STANDARD_INFO_IDENTITY_LENGTH_OFFSET.toLong())
                require(identityLength in 1..MAX_PLACEHOLDER_IDENTITY_BYTES)
                require(
                    CF_STANDARD_INFO_IDENTITY_OFFSET + identityLength <= returned.value &&
                        CF_STANDARD_INFO_IDENTITY_OFFSET + identityLength <= CF_STANDARD_INFO_BUFFER_BYTES,
                )
                buffer.getByteArray(CF_STANDARD_INFO_IDENTITY_OFFSET.toLong(), identityLength)
            }
        }.getOrNull()
    }

    override fun updatePlaceholder(
        path: Path,
        placeholder: WindowsCloudPlaceholder,
        invalidateContent: Boolean,
        preserveSyncState: Boolean,
    ) {
        require(!invalidateContent || !preserveSyncState)
        withFileHandle(path, write = true, exclusive = invalidateContent) { handle ->
            val metadata = placeholder.windowsMetadata(fallbackEpochMillis = null)
            val identity = placeholder.identity.nativeMemory()
            val flags = if (preserveSyncState) {
                0
            } else {
                CF_UPDATE_FLAG_MARK_IN_SYNC or CF_UPDATE_FLAG_VERIFY_IN_SYNC or
                    if (invalidateContent) CF_UPDATE_FLAG_DEHYDRATE else 0
            }
            checkHResult(
                cldApi.CfUpdatePlaceholder(
                    handle,
                    metadata,
                    identity,
                    placeholder.identity.size,
                    null,
                    0,
                    flags,
                    null,
                    null,
                ),
                "update a Windows Cloud Files placeholder",
            )
            identity.clear()
        }
    }

    override fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) {
        withFileHandle(path, write = true) { handle ->
            val identity = placeholder.identity.nativeMemory()
            checkHResult(
                cldApi.CfConvertToPlaceholder(
                    handle,
                    identity,
                    placeholder.identity.size,
                    CF_CONVERT_FLAG_MARK_IN_SYNC,
                    null,
                    null,
                ),
                "convert a local item to a Windows Cloud Files placeholder",
            )
            identity.clear()
        }
    }

    override fun markInSync(path: Path) {
        withFileHandle(path, write = true) { handle ->
            checkHResult(
                cldApi.CfSetInSyncState(handle, CF_IN_SYNC_STATE_IN_SYNC, 0, null),
                "mark a Windows Cloud Files placeholder in sync",
            )
        }
    }

    override fun dehydrate(path: Path): Long {
        if (!Files.isRegularFile(path)) return 0L
        val size = Files.size(path)
        withFileHandle(path, write = true) { handle ->
            checkHResult(
                cldApi.CfDehydratePlaceholder(handle, 0L, -1L, 0, null),
                "dehydrate a Windows Cloud Files placeholder",
            )
        }
        return size
    }

    override fun close() {
        callbacksByConnection.keys.toList().forEach { key -> runCatching { disconnect(key) } }
    }

    private fun dispatchCallback(callbacks: WindowsCloudFilesCallbacks, infoPointer: Pointer, parameters: Pointer) {
        val nativeInfo = CfCallbackInfo(infoPointer).apply { read() }
        val identity = nativeInfo.fileIdentity?.takeIf { nativeInfo.fileIdentityLength > 0 }
            ?.getByteArray(0L, nativeInfo.fileIdentityLength)
        val info = WindowsCloudCallbackInfo(
            connectionKey = nativeInfo.connectionKey,
            transferKey = nativeInfo.transferKey,
            requestKey = nativeInfo.requestKey,
            normalizedPath = nativeInfo.normalizedPath?.toString().orEmpty(),
            fileIdentity = identity,
            fileSize = nativeInfo.fileSize,
            priorityHint = nativeInfo.priorityHint.toInt() and 0xff,
        )
        val type = requireNotNull(currentCallbackType.get()) { "The Cloud Files callback type was not bound." }
        val union = PARAMETERS_UNION_OFFSET
        when (type) {
            CF_CALLBACK_TYPE_FETCH_DATA -> callbacks.fetchData(
                info,
                parameters.getLong(union + 8L),
                parameters.getLong(union + 16L),
            )
            CF_CALLBACK_TYPE_CANCEL_FETCH_DATA -> callbacks.cancelFetchData(
                info,
                parameters.getLong(union + 8L),
                parameters.getLong(union + 16L),
            )
            CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS -> callbacks.fetchPlaceholders(
                info,
                parameters.getPointer(union + 8L)?.getWideString(0L),
            )
            CF_CALLBACK_TYPE_CANCEL_FETCH_PLACEHOLDERS -> callbacks.cancelFetchPlaceholders(info)
            CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION -> callbacks.closed(
                info,
                parameters.getInt(union).and(CF_CALLBACK_CLOSE_COMPLETION_FLAG_DELETED) != 0,
            )
            CF_CALLBACK_TYPE_NOTIFY_DELETE -> callbacks.deleteRequested(info)
            CF_CALLBACK_TYPE_NOTIFY_RENAME -> callbacks.renameRequested(
                info,
                requireNotNull(parameters.getPointer(union + 8L)).getWideString(0L),
            )
        }
    }

    private fun execute(
        info: WindowsCloudCallbackInfo,
        operationType: Int,
        parameterSize: Int,
        fill: (Memory) -> Unit,
    ) {
        val operation = Memory(OPERATION_INFO_SIZE.toLong()).apply {
            clear()
            setInt(0L, OPERATION_INFO_SIZE)
            setInt(4L, operationType)
            setLong(8L, info.connectionKey)
            setLong(16L, info.transferKey)
            setLong(40L, info.requestKey)
        }
        val parameters = Memory(OPERATION_PARAMETERS_SIZE.toLong()).apply {
            clear()
            setInt(0L, parameterSize)
            fill(this)
        }
        checkHResult(cldApi.CfExecute(operation, parameters), "complete a Windows Cloud Files callback")
    }

    private inline fun <T> withFileHandle(
        path: Path,
        write: Boolean,
        exclusive: Boolean = false,
        block: (WinNT.HANDLE) -> T,
    ): T {
        val handle = Kernel32.INSTANCE.CreateFile(
            path.toAbsolutePath().toString(),
            if (write) WinNT.GENERIC_WRITE else WinNT.FILE_READ_ATTRIBUTES,
            if (exclusive) 0 else WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_BACKUP_SEMANTICS,
            null,
        )
        check(handle != WinBase.INVALID_HANDLE_VALUE) { "Could not open the Windows Cloud Files placeholder." }
        return try {
            block(handle)
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private inline fun <T> withFindData(path: Path, fallback: T, block: (WinBase.WIN32_FIND_DATA) -> T): T {
        if (!Files.exists(path)) return fallback
        val findData = WinBase.WIN32_FIND_DATA()
        val handle = Kernel32.INSTANCE.FindFirstFile(path.toAbsolutePath().toString(), findData.pointer)
        if (WinBase.INVALID_HANDLE_VALUE == handle) return fallback
        return try {
            findData.read()
            block(findData)
        } finally {
            Kernel32.INSTANCE.FindClose(handle)
        }
    }

    private fun checkHResult(result: Int, operation: String) {
        check(result >= 0) { "Could not $operation (HRESULT 0x${result.toUInt().toString(16)})." }
    }

    private data class CallbackLifetime(
        val callbacks: List<CldCallback>,
        val registrations: List<CfCallbackRegistration>,
    )

    private inner class NativePlaceholderArray(placeholders: List<WindowsCloudPlaceholder>) {
        private val names = placeholders.map { it.name.wideMemory() }
        private val identities = placeholders.map { it.identity.nativeMemory() }
        private val entries: List<CfPlaceholderCreateInfo> = if (placeholders.isEmpty()) {
            emptyList()
        } else {
            CfPlaceholderCreateInfo().toArray(placeholders.size)
                .map { it as CfPlaceholderCreateInfo }
                .also { array ->
                    placeholders.forEachIndexed { index, placeholder ->
                        array[index].relativeFileName = names[index]
                        array[index].metadata = placeholder.windowsMetadata()
                        array[index].fileIdentity = identities[index]
                        array[index].fileIdentityLength = placeholder.identity.size
                        array[index].flags = CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC
                        array[index].write()
                    }
                }
        }
        val firstPointer: Pointer? get() = entries.firstOrNull()?.pointer

        fun requireSuccessful() {
            entries.forEach { entry ->
                entry.read()
                check(entry.result >= 0) { "Windows rejected a Cloud Files placeholder (HRESULT 0x${entry.result.toUInt().toString(16)})." }
            }
        }
    }

    private companion object {
        val currentCallbackType = ThreadLocal<Int?>()

        const val CF_HYDRATION_POLICY_PROGRESSIVE = 1
        const val CF_HYDRATION_POLICY_MODIFIER_AUTO_DEHYDRATION_ALLOWED = 0x4
        const val CF_POPULATION_POLICY_FULL = 2
        const val CF_INSYNC_POLICY_TRACK_FILE_CREATION_TIME = 0x1
        const val CF_INSYNC_POLICY_TRACK_FILE_LAST_WRITE_TIME = 0x100
        const val CF_INSYNC_POLICY_TRACK_DIRECTORY_CREATION_TIME = 0x10
        const val CF_INSYNC_POLICY_TRACK_DIRECTORY_LAST_WRITE_TIME = 0x200
        const val CF_HARDLINK_POLICY_NONE = 0
        const val CF_PLACEHOLDER_MANAGEMENT_POLICY_DEFAULT = 0
        const val CF_REGISTER_FLAG_UPDATE = 0x1
        const val CF_REGISTER_FLAG_MARK_IN_SYNC_ON_ROOT = 0x4
        const val CF_CONNECT_FLAG_REQUIRE_FULL_FILE_PATH = 0x2

        const val CF_CALLBACK_TYPE_FETCH_DATA = 0
        const val CF_CALLBACK_TYPE_CANCEL_FETCH_DATA = 2
        const val CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS = 3
        const val CF_CALLBACK_TYPE_CANCEL_FETCH_PLACEHOLDERS = 4
        const val CF_CALLBACK_TYPE_NOTIFY_FILE_CLOSE_COMPLETION = 6
        const val CF_CALLBACK_TYPE_NOTIFY_DELETE = 9
        const val CF_CALLBACK_TYPE_NOTIFY_RENAME = 11
        const val CF_CALLBACK_TYPE_NONE = -1
        const val CF_CALLBACK_CLOSE_COMPLETION_FLAG_DELETED = 0x1

        const val CF_OPERATION_TYPE_TRANSFER_DATA = 0
        const val CF_OPERATION_TYPE_TRANSFER_PLACEHOLDERS = 4
        val SYNC_ROOT_ALREADY_UNREGISTERED_RESULTS = setOf(
            0xC000CF13.toInt(), // STATUS_CLOUD_FILE_NOT_UNDER_SYNC_ROOT
            0xD000CF13.toInt(), // HRESULT_FROM_NT(STATUS_CLOUD_FILE_NOT_UNDER_SYNC_ROOT)
            0x80070186.toInt(), // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_NOT_UNDER_SYNC_ROOT)
        )
        const val CF_OPERATION_TYPE_ACK_RENAME = 6
        const val CF_OPERATION_TYPE_ACK_DELETE = 7
        const val STATUS_SUCCESS = 0
        const val STATUS_CLOUD_FILE_UNSUCCESSFUL = -1_073_688_814 // 0xC000CF12

        const val CF_PLACEHOLDER_STATE_PLACEHOLDER = 0x1
        const val CF_PLACEHOLDER_STATE_IN_SYNC = 0x8
        const val CF_CREATE_FLAG_STOP_ON_ERROR = 0x1
        const val CF_PLACEHOLDER_CREATE_FLAG_MARK_IN_SYNC = 0x2
        const val CF_UPDATE_FLAG_MARK_IN_SYNC = 0x2
        const val CF_UPDATE_FLAG_DEHYDRATE = 0x4
        const val CF_UPDATE_FLAG_VERIFY_IN_SYNC = 0x1
        const val CF_CONVERT_FLAG_MARK_IN_SYNC = 0x1
        const val CF_PLACEHOLDER_INFO_STANDARD = 1
        const val MAX_PLACEHOLDER_IDENTITY_BYTES = 4_096
        const val CF_STANDARD_INFO_IDENTITY_LENGTH_OFFSET = 56
        const val CF_STANDARD_INFO_IDENTITY_OFFSET = 60
        const val CF_STANDARD_INFO_BUFFER_BYTES = CF_STANDARD_INFO_IDENTITY_OFFSET + MAX_PLACEHOLDER_IDENTITY_BYTES
        const val CF_IN_SYNC_STATE_IN_SYNC = 1
        const val FILE_ATTRIBUTE_PINNED = 0x0008_0000

        const val PARAMETERS_UNION_OFFSET = 8L
        const val OPERATION_INFO_SIZE = 48
        const val OPERATION_PARAMETERS_SIZE = 64
        const val TRANSFER_PARAMETERS_SIZE = 40
        const val PLACEHOLDER_PARAMETERS_SIZE = 40
        const val ACK_PARAMETERS_SIZE = 16
        const val TRANSFER_FLAGS_OFFSET = PARAMETERS_UNION_OFFSET
        const val TRANSFER_STATUS_OFFSET = PARAMETERS_UNION_OFFSET + 4L
        const val TRANSFER_BUFFER_OFFSET = PARAMETERS_UNION_OFFSET + 8L
        const val TRANSFER_OFFSET_OFFSET = PARAMETERS_UNION_OFFSET + 16L
        const val TRANSFER_LENGTH_OFFSET = PARAMETERS_UNION_OFFSET + 24L
        const val PLACEHOLDERS_FLAGS_OFFSET = PARAMETERS_UNION_OFFSET
        const val PLACEHOLDERS_STATUS_OFFSET = PARAMETERS_UNION_OFFSET + 4L
        const val PLACEHOLDERS_TOTAL_OFFSET = PARAMETERS_UNION_OFFSET + 8L
        const val PLACEHOLDERS_ARRAY_OFFSET = PARAMETERS_UNION_OFFSET + 16L
        const val PLACEHOLDERS_COUNT_OFFSET = PARAMETERS_UNION_OFFSET + 24L
        const val PLACEHOLDERS_PROCESSED_OFFSET = PARAMETERS_UNION_OFFSET + 28L
        const val ACK_FLAGS_OFFSET = PARAMETERS_UNION_OFFSET
        const val ACK_STATUS_OFFSET = PARAMETERS_UNION_OFFSET + 4L
    }
}

internal interface CldApi : StdCallLibrary {
    fun CfRegisterSyncRoot(path: WString, registration: CfSyncRegistration, policies: CfSyncPolicies, flags: Int): Int
    fun CfUnregisterSyncRoot(path: WString): Int
    fun CfConnectSyncRoot(path: WString, callbackTable: Pointer, context: Pointer?, flags: Int, key: LongByReference): Int
    fun CfDisconnectSyncRoot(key: Long): Int
    fun CfCreatePlaceholders(path: WString, placeholders: Pointer?, count: Int, flags: Int, processed: IntByReference): Int
    fun CfExecute(operationInfo: Pointer, operationParameters: Pointer): Int
    fun CfGetPlaceholderStateFromFindData(findData: Pointer): Int
    fun CfGetPlaceholderInfo(
        handle: WinNT.HANDLE,
        infoClass: Int,
        infoBuffer: Pointer,
        infoBufferLength: Int,
        returnedLength: IntByReference?,
    ): Int
    fun CfUpdatePlaceholder(
        handle: WinNT.HANDLE,
        metadata: CfFsMetadata?,
        identity: Pointer?,
        identityLength: Int,
        ranges: Pointer?,
        rangeCount: Int,
        flags: Int,
        usn: LongByReference?,
        overlapped: Pointer?,
    ): Int
    fun CfConvertToPlaceholder(
        handle: WinNT.HANDLE,
        identity: Pointer?,
        identityLength: Int,
        flags: Int,
        usn: LongByReference?,
        overlapped: Pointer?,
    ): Int
    fun CfSetInSyncState(handle: WinNT.HANDLE, state: Int, flags: Int, usn: LongByReference?): Int
    fun CfDehydratePlaceholder(handle: WinNT.HANDLE, offset: Long, length: Long, flags: Int, overlapped: Pointer?): Int
}

internal interface KernelFileApi : StdCallLibrary {
    fun GetCompressedFileSizeW(path: WString, high: IntByReference): Int
}

internal fun interface CldCallback : StdCallLibrary.StdCallCallback {
    fun invoke(info: Pointer, parameters: Pointer)
}

@Structure.FieldOrder("structSize", "providerName", "providerVersion", "syncRootIdentityPointer", "syncRootIdentityLength", "fileIdentity", "fileIdentityLength", "providerId")
internal class CfSyncRegistration : Structure() {
    @JvmField var structSize: Int = 0
    @JvmField var providerName: WString? = null
    @JvmField var providerVersion: WString? = null
    @JvmField var syncRootIdentityPointer: Pointer? = null
    @JvmField var syncRootIdentityLength: Int = 0
    @JvmField var fileIdentity: Pointer? = null
    @JvmField var fileIdentityLength: Int = 0
    @JvmField var providerId: Guid.GUID = Guid.GUID()
}

@Structure.FieldOrder("primary", "modifier")
internal class CfPolicy() : Structure() {
    @JvmField var primary: Short = 0
    @JvmField var modifier: Short = 0
    constructor(primary: Int, modifier: Int) : this() {
        this.primary = primary.toShort()
        this.modifier = modifier.toShort()
    }
}

@Structure.FieldOrder("structSize", "hydration", "population", "inSync", "hardLink", "placeholderManagement")
internal class CfSyncPolicies : Structure() {
    @JvmField var structSize: Int = 0
    @JvmField var hydration: CfPolicy = CfPolicy()
    @JvmField var population: CfPolicy = CfPolicy()
    @JvmField var inSync: Int = 0
    @JvmField var hardLink: Int = 0
    @JvmField var placeholderManagement: Int = 0
}

@Structure.FieldOrder("type", "callback")
internal class CfCallbackRegistration : Structure() {
    @JvmField var type: Int = 0
    @JvmField var callback: CldCallback? = null
}

@Structure.FieldOrder("creationTime", "lastAccessTime", "lastWriteTime", "changeTime", "fileAttributes", "fileSize")
internal class CfFsMetadata : Structure() {
    @JvmField var creationTime: Long = 0L
    @JvmField var lastAccessTime: Long = 0L
    @JvmField var lastWriteTime: Long = 0L
    @JvmField var changeTime: Long = 0L
    @JvmField var fileAttributes: Int = 0
    @JvmField var fileSize: Long = 0L
}

@Structure.FieldOrder("relativeFileName", "metadata", "fileIdentity", "fileIdentityLength", "flags", "result", "createUsn")
internal class CfPlaceholderCreateInfo : Structure() {
    @JvmField var relativeFileName: Pointer? = null
    @JvmField var metadata: CfFsMetadata = CfFsMetadata()
    @JvmField var fileIdentity: Pointer? = null
    @JvmField var fileIdentityLength: Int = 0
    @JvmField var flags: Int = 0
    @JvmField var result: Int = 0
    @JvmField var createUsn: Long = 0L
}

@Structure.FieldOrder(
    "structSize", "connectionKey", "callbackContext", "volumeGuidName", "volumeDosName",
    "volumeSerialNumber", "syncRootFileId", "syncRootIdentity", "syncRootIdentityLength",
    "fileId", "fileSize", "fileIdentity", "fileIdentityLength", "normalizedPath", "transferKey",
    "priorityHint", "correlationVector", "processInfo", "requestKey",
)
internal class CfCallbackInfo(pointer: Pointer) : Structure(pointer) {
    @JvmField var structSize: Int = 0
    @JvmField var connectionKey: Long = 0L
    @JvmField var callbackContext: Pointer? = null
    @JvmField var volumeGuidName: WString? = null
    @JvmField var volumeDosName: WString? = null
    @JvmField var volumeSerialNumber: Int = 0
    @JvmField var syncRootFileId: Long = 0L
    @JvmField var syncRootIdentity: Pointer? = null
    @JvmField var syncRootIdentityLength: Int = 0
    @JvmField var fileId: Long = 0L
    @JvmField var fileSize: Long = 0L
    @JvmField var fileIdentity: Pointer? = null
    @JvmField var fileIdentityLength: Int = 0
    @JvmField var normalizedPath: WString? = null
    @JvmField var transferKey: Long = 0L
    @JvmField var priorityHint: Byte = 0
    @JvmField var correlationVector: Pointer? = null
    @JvmField var processInfo: Pointer? = null
    @JvmField var requestKey: Long = 0L
}

internal fun WindowsCloudPlaceholder.windowsMetadata(
    fallbackEpochMillis: Long? = System.currentTimeMillis(),
): CfFsMetadata = CfFsMetadata().apply {
    val timestamp = (lastModifiedEpochMillis ?: fallbackEpochMillis)?.let(::windowsFileTime) ?: 0L
    creationTime = timestamp
    lastAccessTime = timestamp
    lastWriteTime = timestamp
    changeTime = timestamp
    fileAttributes = if (directory) WinNT.FILE_ATTRIBUTE_DIRECTORY else WinNT.FILE_ATTRIBUTE_ARCHIVE
    fileSize = size
    write()
}

internal fun windowsFileTime(epochMillis: Long): Long = Math.multiplyExact(
    Math.addExact(epochMillis, WINDOWS_EPOCH_OFFSET_MILLIS),
    WINDOWS_FILE_TIME_TICKS_PER_MILLISECOND,
)

private fun ByteArray.nativeMemory(): Memory = Memory(size.toLong()).also { memory ->
    memory.write(0L, this, 0, size)
}

private fun String.wideMemory(): Memory = Memory(((length + 1) * Native.WCHAR_SIZE).toLong()).also { memory ->
    memory.setWideString(0L, this)
}

internal fun isWindowsDesktop(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("windows")

internal data class WindowsCloudNativeLayoutSizes(
    val registration: Int,
    val policies: Int,
    val fileSystemMetadata: Int,
    val placeholder: Int,
    val callbackInfo: Int,
)

internal fun windowsCloudNativeLayoutSizes(): WindowsCloudNativeLayoutSizes = WindowsCloudNativeLayoutSizes(
    registration = CfSyncRegistration().size(),
    policies = CfSyncPolicies().size(),
    fileSystemMetadata = CfFsMetadata().size(),
    placeholder = CfPlaceholderCreateInfo().size(),
    callbackInfo = CfCallbackInfo(Memory(160L)).size(),
)

private const val WINDOWS_EPOCH_OFFSET_MILLIS = 11_644_473_600_000L
private const val WINDOWS_FILE_TIME_TICKS_PER_MILLISECOND = 10_000L
