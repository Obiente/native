package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

internal enum class DesktopActivationKind(val wireValue: String) {
    Background("background"),
    ShowWindow("show"),
    UpdateHandoffFailed("update-handoff-failed"),
    ;

    companion object {
        fun fromWireValue(value: String): DesktopActivationKind? = entries.firstOrNull { it.wireValue == value }
    }
}

internal data class DesktopActivationRequest(
    val sequence: Long,
    val kind: DesktopActivationKind,
)

internal sealed interface DesktopSingleInstanceStart {
    data class Primary(val instance: DesktopSingleInstance) : DesktopSingleInstanceStart
    data object Forwarded : DesktopSingleInstanceStart
    data object Failed : DesktopSingleInstanceStart
}

internal class DesktopSingleInstance private constructor(
    private val lockChannel: FileChannel,
    private val processLock: FileLock,
    private val server: ServerSocket,
    private val endpointFile: File,
    private val endpointToken: String,
) : AutoCloseable {
    private val activationSequence = AtomicLong(0L)
    private val activationQueue = Channel<DesktopActivationRequest>(Channel.UNLIMITED)
    val activations: Flow<DesktopActivationRequest> = activationQueue.receiveAsFlow()
    private val serverThread = Thread(::serveActivations, "nextcloud-native-instance-activation").apply {
        isDaemon = true
        start()
    }

    private fun serveActivations() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching {
                socket.use { connection ->
                    connection.soTimeout = ACTIVATION_TIMEOUT_MILLIS
                    val suppliedToken = connection.getInputStream().readBoundedLine(MAX_ENDPOINT_BYTES)
                    val activationKind = connection.getInputStream()
                        .readBoundedLine(MAX_ACTIVATION_KIND_BYTES)
                        ?.let(DesktopActivationKind::fromWireValue)
                    val authenticated = suppliedToken != null && activationKind != null && MessageDigest.isEqual(
                        suppliedToken.encodeToByteArray(),
                        endpointToken.encodeToByteArray(),
                    )
                    val accepted = authenticated && activationQueue.trySend(
                        DesktopActivationRequest(
                            activationSequence.incrementAndGet(),
                            requireNotNull(activationKind),
                        ),
                    ).isSuccess
                    connection.getOutputStream().write(if (accepted) ACTIVATION_ACCEPTED else ACTIVATION_REJECTED)
                    connection.getOutputStream().flush()
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        activationQueue.close()
        serverThread.interrupt()
        runCatching {
            if (endpointFile.isFile && endpointFile.length() <= MAX_ENDPOINT_BYTES) {
                val endpoint = endpointFile.readText()
                if (endpoint.substringAfter('\n', "") == endpointToken) Files.deleteIfExists(endpointFile.toPath())
            }
        }
        runCatching { processLock.release() }
        runCatching { lockChannel.close() }
    }

    internal companion object {
        fun forwardToExisting(
            activationKind: DesktopActivationKind,
            runtimeDirectory: File = defaultDesktopRuntimeDirectory(),
            forwardAttempts: Int = DEFAULT_FORWARD_ATTEMPTS,
            forwardDelayMillis: Long = DEFAULT_FORWARD_DELAY_MILLIS,
        ): Boolean {
            require(forwardAttempts > 0 && forwardDelayMillis >= 0L)
            val directory = runtimeDirectory.toPath().toAbsolutePath().normalize()
            return forwardActivation(
                directory.resolve(INSTANCE_ENDPOINT_NAME).toFile(),
                activationKind,
                forwardAttempts,
                forwardDelayMillis,
            )
        }

        fun waitForPrimary(
            runtimeDirectory: File = defaultDesktopRuntimeDirectory(),
            retryDelayMillis: Long = DEFAULT_SERVICE_LOCK_RETRY_MILLIS,
        ): DesktopSingleInstanceStart.Primary? {
            require(retryDelayMillis > 0L)
            val directory = runtimeDirectory.toPath().toAbsolutePath().normalize()
            return runCatching {
                Files.createDirectories(directory)
                check(!Files.isSymbolicLink(directory)) { "The desktop runtime folder cannot be a symlink." }
                val lockPath = directory.resolve(INSTANCE_LOCK_NAME)
                check(!Files.isSymbolicLink(lockPath)) { "The desktop process lock cannot be a symlink." }
                while (!Thread.currentThread().isInterrupted) {
                    val channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                    val lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    } catch (failure: Throwable) {
                        channel.close()
                        throw failure
                    }
                    if (lock != null) {
                        return@runCatching createPrimary(directory.toFile(), channel, lock)
                    }
                    channel.close()
                    Thread.sleep(retryDelayMillis)
                }
                null
            }.getOrNull()
        }

        fun acquire(
            runtimeDirectory: File = defaultDesktopRuntimeDirectory(),
            forwardAttempts: Int = DEFAULT_FORWARD_ATTEMPTS,
            forwardDelayMillis: Long = DEFAULT_FORWARD_DELAY_MILLIS,
            activationKind: DesktopActivationKind = DesktopActivationKind.ShowWindow,
        ): DesktopSingleInstanceStart {
            require(forwardAttempts > 0 && forwardDelayMillis >= 0L)
            val directory = runtimeDirectory.toPath().toAbsolutePath().normalize()
            return runCatching {
                Files.createDirectories(directory)
                check(!Files.isSymbolicLink(directory)) { "The desktop runtime folder cannot be a symlink." }
                val lockPath = directory.resolve(INSTANCE_LOCK_NAME)
                check(!Files.isSymbolicLink(lockPath)) { "The desktop process lock cannot be a symlink." }
                val lockChannel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                val lock = try {
                    lockChannel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    lockChannel.close()
                    if (
                        forwardActivation(
                            directory.resolve(INSTANCE_ENDPOINT_NAME).toFile(),
                            activationKind,
                            forwardAttempts,
                            forwardDelayMillis,
                        )
                    ) {
                        return@runCatching DesktopSingleInstanceStart.Forwarded
                    }
                    val replacementChannel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                    val replacementLock = try {
                        replacementChannel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (replacementLock == null) {
                        replacementChannel.close()
                        return@runCatching DesktopSingleInstanceStart.Failed
                    }
                    return@runCatching createPrimary(directory.toFile(), replacementChannel, replacementLock)
                }
                createPrimary(directory.toFile(), lockChannel, lock)
            }.getOrElse { DesktopSingleInstanceStart.Failed }
        }

        private fun createPrimary(
            runtimeDirectory: File,
            lockChannel: FileChannel,
            lock: FileLock,
        ): DesktopSingleInstanceStart.Primary {
            var server: ServerSocket? = null
            try {
                val activeServer = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
                server = activeServer
                val token = ByteArray(32).also(SecureRandom()::nextBytes).lowercaseHex()
                val endpoint = runtimeDirectory.resolve(INSTANCE_ENDPOINT_NAME)
                writeEndpoint(endpoint, "${activeServer.localPort}\n$token")
                return DesktopSingleInstanceStart.Primary(
                    DesktopSingleInstance(lockChannel, lock, activeServer, endpoint, token),
                )
            } catch (failure: Throwable) {
                runCatching { server?.close() }
                runCatching { lock.release() }
                runCatching { lockChannel.close() }
                throw failure
            }
        }

        private fun writeEndpoint(target: File, contents: String) {
            val temporary = Files.createTempFile(requireNotNull(target.parentFile).toPath(), ".instance-", ".tmp")
            try {
                runCatching {
                    Files.setPosixFilePermissions(
                        temporary,
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    )
                }
                Files.writeString(temporary, contents)
                try {
                    Files.move(
                        temporary,
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun forwardActivation(
            endpoint: File,
            activationKind: DesktopActivationKind,
            attempts: Int,
            delayMillis: Long,
        ): Boolean {
            repeat(attempts) { attempt ->
                if (readEndpoint(endpoint)?.let { sendActivation(it, activationKind) } == true) return true
                if (attempt + 1 < attempts && delayMillis > 0L) Thread.sleep(delayMillis)
            }
            return false
        }

        private fun readEndpoint(endpoint: File): Pair<Int, String>? = runCatching {
            check(Files.isRegularFile(endpoint.toPath(), LinkOption.NOFOLLOW_LINKS))
            check(endpoint.length() in 1..MAX_ENDPOINT_BYTES.toLong())
            val lines = endpoint.readLines()
            check(lines.size == 2)
            val port = lines[0].toInt()
            val token = lines[1]
            check(port in 1..65_535 && token.length == 64 && token.all { it in HEX_DIGITS })
            port to token
        }.getOrNull()

        private fun sendActivation(
            endpoint: Pair<Int, String>,
            activationKind: DesktopActivationKind,
        ): Boolean = runCatching {
            Socket(InetAddress.getLoopbackAddress(), endpoint.first).use { socket ->
                socket.soTimeout = ACTIVATION_TIMEOUT_MILLIS
                socket.getOutputStream().write(
                    (endpoint.second + "\n" + activationKind.wireValue + "\n").encodeToByteArray(),
                )
                socket.getOutputStream().flush()
                socket.getInputStream().read() == ACTIVATION_ACCEPTED
            }
        }.getOrDefault(false)
    }
}

internal fun defaultDesktopRuntimeDirectory(): File {
    val osName = System.getProperty("os.name", "")
    if (osName.startsWith("Windows", ignoreCase = true)) {
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Local")
        return File(localAppData, "Nextcloud Native/Runtime")
    }
    val runtimeRoot = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(runtimeRoot, "nextcloud-native/runtime")
}

private fun InputStream.readBoundedLine(maximumBytes: Int): String? {
    val bytes = ArrayList<Byte>(maximumBytes)
    while (bytes.size <= maximumBytes) {
        when (val value = read()) {
            -1 -> return null
            '\n'.code -> return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            '\r'.code -> Unit
            else -> bytes += value.toByte()
        }
    }
    return null
}

private fun ByteArray.lowercaseHex(): String = buildString(size * 2) {
    this@lowercaseHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val INSTANCE_LOCK_NAME = "nextcloud-native.lock"
private const val INSTANCE_ENDPOINT_NAME = "nextcloud-native.endpoint"
private const val DEFAULT_FORWARD_ATTEMPTS = 40
private const val DEFAULT_FORWARD_DELAY_MILLIS = 50L
private const val DEFAULT_SERVICE_LOCK_RETRY_MILLIS = 1_000L
private const val ACTIVATION_TIMEOUT_MILLIS = 1_000
private const val MAX_ENDPOINT_BYTES = 256
private const val MAX_ACTIVATION_KIND_BYTES = 64
private const val ACTIVATION_ACCEPTED = 1
private const val ACTIVATION_REJECTED = 0
private const val HEX_DIGITS = "0123456789abcdef"
