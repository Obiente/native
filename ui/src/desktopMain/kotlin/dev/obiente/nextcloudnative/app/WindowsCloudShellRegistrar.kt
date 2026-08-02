package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal interface WindowsCloudShellRegistrar {
    val available: Boolean
    fun register(root: Path, accountId: String, syncRootIdentity: ByteArray): Boolean
    fun unregister(accountId: String): Boolean
}

internal class PackagedWindowsCloudShellRegistrar(
    launcherPath: String? = packagedDesktopLauncherPath(),
    private val processRunner: (List<String>, Long) -> Int? = ::runWindowsShellRegistrar,
) : WindowsCloudShellRegistrar {
    private val installationDirectory = launcherPath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.parentFile
    private val helper = installationDirectory?.resolve(WINDOWS_SHELL_REGISTRAR_NAME)
    private val icon = installationDirectory?.resolve(WINDOWS_SHELL_ICON_NAME)

    override val available: Boolean
        get() = helper?.isFile == true && icon?.isFile == true

    override fun register(root: Path, accountId: String, syncRootIdentity: ByteArray): Boolean {
        requireWindowsShellAccountId(accountId)
        require(syncRootIdentity.isNotEmpty() && syncRootIdentity.size <= MAX_SYNC_ROOT_IDENTITY_BYTES)
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRoot) && !Files.isSymbolicLink(normalizedRoot))
        val executable = helper?.takeIf(File::isFile) ?: return false
        val iconResource = icon?.takeIf(File::isFile) ?: return false
        return runCatching {
            processRunner(
                listOf(
                    executable.absolutePath,
                    "register",
                    normalizedRoot.toString(),
                    accountId,
                    iconResource.absolutePath,
                    syncRootIdentity.lowercaseHex(),
                ),
                REGISTRATION_TIMEOUT_SECONDS,
            ) == 0
        }.getOrDefault(false)
    }

    override fun unregister(accountId: String): Boolean {
        requireWindowsShellAccountId(accountId)
        val executable = helper?.takeIf(File::isFile) ?: return false
        return runCatching {
            processRunner(
                listOf(executable.absolutePath, "unregister", accountId),
                REGISTRATION_TIMEOUT_SECONDS,
            ) == 0
        }.getOrDefault(false)
    }

    private companion object {
        const val MAX_SYNC_ROOT_IDENTITY_BYTES = 4_096
        const val REGISTRATION_TIMEOUT_SECONDS = 30L
    }
}

internal enum class WindowsSyncRootRegistrationMode {
    BrandedShell,
    CloudFilesOnly,
}

internal fun migrateWindowsSyncRootRegistration(
    shellAvailable: Boolean,
    unregisterCloudFilesRoot: () -> Boolean,
    registerBrandedShellRoot: () -> Boolean,
    registerCloudFilesRoot: () -> Unit,
): WindowsSyncRootRegistrationMode {
    if (shellAvailable) {
        if (registerBrandedShellRoot()) return WindowsSyncRootRegistrationMode.BrandedShell
        if (unregisterCloudFilesRoot() && registerBrandedShellRoot()) {
            return WindowsSyncRootRegistrationMode.BrandedShell
        }
    }
    registerCloudFilesRoot()
    return WindowsSyncRootRegistrationMode.CloudFilesOnly
}

internal fun windowsCloudShellAccountId(root: Path): String? {
    val name = root.fileName?.toString() ?: return null
    val accountId = name.removeSuffix(WINDOWS_CLOUD_ROOT_GENERATION_SUFFIX)
    return accountId.takeIf(::isWindowsShellAccountId)
}

private fun requireWindowsShellAccountId(accountId: String) {
    require(isWindowsShellAccountId(accountId))
}

private fun isWindowsShellAccountId(accountId: String): Boolean =
    accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.lowercaseHex(): String {
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = HEX_DIGITS[value ushr 4]
        output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return output.concatToString()
}

private fun runWindowsShellRegistrar(command: List<String>, timeoutSeconds: Long): Int? {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return process.exitValue()
    process.destroy()
    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    return null
}

private const val WINDOWS_CLOUD_ROOT_GENERATION_SUFFIX = "-v2"
internal const val WINDOWS_SHELL_REGISTRAR_NAME = "NextcloudNativeShellRegistrar.exe"
internal const val WINDOWS_SHELL_ICON_NAME = "NextcloudNative.ico"
private const val HEX_DIGITS = "0123456789abcdef"
