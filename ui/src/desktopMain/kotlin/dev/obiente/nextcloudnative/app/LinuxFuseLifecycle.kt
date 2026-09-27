package dev.obiente.nextcloudnative.app

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import jnr.posix.POSIXFactory

internal fun linuxEffectiveProcessUid(): Long = Integer.toUnsignedLong(POSIXFactory.getPOSIX().geteuid())

internal fun linuxEffectiveProcessGid(): Long = Integer.toUnsignedLong(POSIXFactory.getPOSIX().getegid())

internal fun linuxFuseConnectionIdForMount(
    mountPoint: Path,
    mountInfo: String = runCatching { Files.readString(Path.of("/proc/self/mountinfo")) }.getOrDefault(""),
): Int? {
    val encodedMountPoint = mountPoint.toAbsolutePath().normalize().toString()
        .replace("\\", "\\134")
        .replace(" ", "\\040")
        .replace("\t", "\\011")
        .replace("\n", "\\012")
    return mountInfo.lineSequence().firstNotNullOfOrNull { line ->
        val fields = line.split(' ')
        val separator = fields.indexOf("-")
        if (
            fields.size < 7 || separator < 6 || separator + 2 >= fields.size ||
            fields[4] != encodedMountPoint ||
            fields[separator + 1].let { type -> type != "fuse" && !type.startsWith("fuse.") } ||
            fields[separator + 2] != "nextcloud-native"
        ) return@firstNotNullOfOrNull null
        fields[2].substringAfter(':', "").toIntOrNull()
    }
}

internal fun openLinuxFuseAbortHandle(connectionId: Int): LinuxFuseAbortHandle? {
    require(connectionId >= 0)
    return openLinuxFuseAbortHandle(Path.of("/sys/fs/fuse/connections", connectionId.toString(), "abort"))
}

internal fun openLinuxFuseAbortHandle(path: Path): LinuxFuseAbortHandle? = runCatching {
    ChannelLinuxFuseAbortHandle(Files.newByteChannel(path, StandardOpenOption.WRITE))
}.getOrNull()

internal interface LinuxFuseAbortHandle : AutoCloseable {
    fun abortBestEffort()
}

internal fun runLinuxFuseUnmountLifecycle(
    abortHandle: LinuxFuseAbortHandle?,
    detach: () -> Unit,
    cleanup: (detached: Boolean) -> Unit,
) {
    var detached = false
    try {
        detach()
        detached = true
    } finally {
        abortHandle?.abortBestEffort()
        runCatching { abortHandle?.close() }
        cleanup(detached)
    }
}

private class ChannelLinuxFuseAbortHandle(
    private val channel: SeekableByteChannel,
) : LinuxFuseAbortHandle {
    override fun abortBestEffort() {
        runCatching { channel.write(ByteBuffer.wrap("1\n".encodeToByteArray())) }
    }

    override fun close() = channel.close()
}

internal const val MAX_UNSIGNED_UNIX_ID = 0xffff_ffffL
