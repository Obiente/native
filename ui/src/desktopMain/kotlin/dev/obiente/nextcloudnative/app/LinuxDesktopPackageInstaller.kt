package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun runLinuxNativePackageInstaller(
    packageFile: File,
    commandResolver: (File) -> List<String>? = ::linuxNativePackageInstallerCommand,
    commandRunner: (List<String>) -> Int = ::runNativePackageInstallerCommand,
): Boolean {
    check(Files.isRegularFile(packageFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "The verified Linux update package is no longer a regular file."
    }
    val command = commandResolver(packageFile) ?: return false
    val exitCode = commandRunner(command)
    check(exitCode == 0) { "The system package transaction failed with exit code $exitCode." }
    return true
}

private fun runNativePackageInstallerCommand(command: List<String>): Int {
    val process = ProcessBuilder(command).inheritIO().start()
    return try {
        process.waitFor()
    } catch (interrupted: InterruptedException) {
        process.destroy()
        Thread.currentThread().interrupt()
        throw IOException("The system package transaction was interrupted.", interrupted)
    }
}

internal fun linuxNativePackageInstallerCommand(
    packageFile: File,
    executableAvailable: (File) -> Boolean = { executable -> executable.isFile && executable.canExecute() },
): List<String>? {
    val format = packageFile.extension.lowercase()
    if (format !in DESKTOP_LINUX_PACKAGE_FORMATS) return null
    val authorizationBroker = File("/usr/bin/pkexec")
    if (!executableAvailable(authorizationBroker)) return null
    val packagePath = packageFile.toPath().toAbsolutePath().normalize().toString()
    return when (format) {
        "rpm" -> rpmPackageInstallerCommand(authorizationBroker, packagePath, executableAvailable)
        "deb" -> debPackageInstallerCommand(authorizationBroker, packagePath, executableAvailable)
        else -> null
    }
}

private fun rpmPackageInstallerCommand(
    authorizationBroker: File,
    packagePath: String,
    executableAvailable: (File) -> Boolean,
): List<String>? {
    val dnf5 = File("/usr/bin/dnf5")
    if (executableAvailable(dnf5)) {
        return listOf(
            authorizationBroker.absolutePath,
            dnf5.absolutePath,
            "--assumeyes",
            "install",
            "--no-allow-downgrade",
            packagePath,
        )
    }
    val dnf = File("/usr/bin/dnf")
    if (!executableAvailable(dnf)) return null
    return listOf(
        authorizationBroker.absolutePath,
        dnf.absolutePath,
        "--assumeyes",
        "install",
        packagePath,
    )
}

private fun debPackageInstallerCommand(
    authorizationBroker: File,
    packagePath: String,
    executableAvailable: (File) -> Boolean,
): List<String>? {
    val aptGet = File("/usr/bin/apt-get")
    if (!executableAvailable(aptGet)) return null
    return listOf(
        authorizationBroker.absolutePath,
        aptGet.absolutePath,
        "--yes",
        "--no-remove",
        "install",
        packagePath,
    )
}
