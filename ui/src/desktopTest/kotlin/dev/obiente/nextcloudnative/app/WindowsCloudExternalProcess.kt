package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Native Cloud Files tests act on a sync root from another process, as Explorer or an editor
 * would. Cloud Files sends no close notification for I/O by the connected provider's own process.
 */
internal fun runExternalPowerShell(script: String, environment: Map<String, String>) {
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        .redirectErrorStream(true)
        .apply { environment().putAll(environment) }
        .start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "External process failed: $output" }
}

/** Overwrites from offset zero without truncating, like an editor saving a longer file. */
internal fun editInPlaceFromAnotherProcess(path: Path, bytes: ByteArray) = runExternalPowerShell(
    "\$s = [IO.File]::Open(\$env:NATIVE_EDIT_PATH, 'Open', 'Write', 'ReadWrite');" +
        "\$b = [Convert]::FromBase64String(\$env:NATIVE_EDIT_BYTES); \$s.Write(\$b, 0, \$b.Length); \$s.Close()",
    mapOf(
        "NATIVE_EDIT_PATH" to path.toAbsolutePath().toString(),
        "NATIVE_EDIT_BYTES" to Base64.getEncoder().encodeToString(bytes),
    ),
)

/**
 * Holds a read handle in another process until closed, as an open document would. [share] is the
 * .NET FileShare mode granted to other openers.
 */
internal class ExternalOpenHandle(path: Path, share: String = "ReadWrite") : AutoCloseable {
    private val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "\$s = [IO.File]::Open(\$env:NATIVE_HOLD_PATH, 'Open', 'Read', '$share'); " +
            "[Console]::Out.WriteLine('open'); [Console]::Out.Flush(); [void][Console]::In.ReadLine(); \$s.Close()",
    ).redirectErrorStream(true)
        .apply { environment()["NATIVE_HOLD_PATH"] = path.toAbsolutePath().toString() }
        .start()

    init {
        val ready = process.inputStream.bufferedReader().readLine()
        check(ready == "open") { "The external handle did not open: $ready" }
    }

    override fun close() {
        process.outputStream.bufferedWriter().use { it.write("close"); it.newLine() }
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "The external handle did not close." }
    }
}
