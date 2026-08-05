package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.TimeUnit

internal data class DesktopFileSyncRuntimeConditions(
    val networkAvailable: Boolean?,
    val unmeteredNetwork: Boolean?,
    val hasBattery: Boolean,
    val batteryPercent: Int?,
    val externalPowerConnected: Boolean?,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100)
    }

    fun allows(configuration: FileSyncConfiguration): Boolean {
        val networkAllowed = when (configuration.networkPolicy) {
            FileSyncNetworkPolicy.AnyConnection -> networkAvailable != false
            FileSyncNetworkPolicy.Unmetered -> networkAvailable != false && unmeteredNetwork == true
        }
        val powerAllowed = when (configuration.powerPolicy) {
            FileSyncPowerPolicy.AnyPower -> true
            FileSyncPowerPolicy.BatteryNotLow -> !hasBattery ||
                externalPowerConnected == true ||
                (batteryPercent ?: -1) >= BATTERY_LOW_PERCENT
            FileSyncPowerPolicy.Charging -> !hasBattery || externalPowerConnected == true
        }
        return networkAllowed && powerAllowed
    }

    fun networkState(configuration: FileSyncConfiguration): FileSyncNetworkState =
        liveFileSyncNetworkState(networkAvailable, unmeteredNetwork, configuration.networkPolicy)

    private companion object {
        const val BATTERY_LOW_PERCENT = 15
    }
}

internal fun desktopFileSyncRuntimeConditions(): DesktopFileSyncRuntimeConditions = when {
    isWindowsDesktop() -> windowsFileSyncRuntimeConditions()
    System.getProperty("os.name").orEmpty().lowercase().contains("linux") ->
        linuxFileSyncRuntimeConditions()
    else -> DesktopFileSyncRuntimeConditions(
        networkAvailable = null,
        unmeteredNetwork = null,
        hasBattery = false,
        batteryPercent = null,
        externalPowerConnected = null,
    )
}

private fun linuxFileSyncRuntimeConditions(): DesktopFileSyncRuntimeConditions {
    val supplies = File("/sys/class/power_supply").listFiles().orEmpty()
    val batteries = supplies.filter { it.resolve("type").readProbeText() == "Battery" }
    val batteryPercent = batteries.mapNotNull { it.resolve("capacity").readProbeText()?.toIntOrNull() }.minOrNull()
    val externalPower = supplies
        .filter { it.resolve("type").readProbeText() in setOf("Mains", "USB", "USB_C", "Wireless") }
        .mapNotNull { it.resolve("online").readProbeText()?.toIntOrNull() }
        .takeIf(List<Int>::isNotEmpty)
        ?.any { it == 1 }
    val networkProbe = runDesktopProbe(
        "nmcli",
        "-t",
        "-f",
        "GENERAL.STATE,GENERAL.METERED",
        "device",
        "show",
    )
    return DesktopFileSyncRuntimeConditions(
        networkAvailable = parseNmcliConnectivityProbe(networkProbe),
        unmeteredNetwork = parseNmcliMeteredProbe(networkProbe),
        hasBattery = batteries.isNotEmpty(),
        batteryPercent = batteryPercent,
        externalPowerConnected = externalPower,
    )
}

private fun windowsFileSyncRuntimeConditions(): DesktopFileSyncRuntimeConditions {
    val power = runCatching {
        DesktopSystemPowerStatus().also { status ->
            val kernel = Native.load("kernel32", DesktopKernelPowerApi::class.java)
            check(kernel.GetSystemPowerStatus(status) != 0)
            status.read()
        }
    }.getOrNull()
    val batteryFlag = power?.BatteryFlag?.toInt()?.and(0xff)
    val hasBattery = batteryFlag != null && batteryFlag and 0x80 == 0
    val batteryPercent = power?.BatteryLifePercent?.toInt()?.and(0xff)?.takeIf { it <= 100 }
    val externalPower = power?.ACLineStatus?.toInt()?.and(0xff)?.let { status ->
        when (status) {
            0 -> false
            1 -> true
            else -> null
        }
    }
    val networkCost = runDesktopProbe(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "${'$'}profile = [Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]::GetInternetConnectionProfile(); if (${'$'}null -eq ${'$'}profile) { 'Disconnected' } else { ${'$'}profile.GetConnectionCost().NetworkCostType }",
    )?.trim()
    return DesktopFileSyncRuntimeConditions(
        networkAvailable = when (networkCost) {
            "Disconnected" -> false
            "Unrestricted", "Fixed", "Variable" -> true
            else -> null
        },
        unmeteredNetwork = when (networkCost) {
            "Unrestricted" -> true
            "Fixed", "Variable" -> false
            else -> null
        },
        hasBattery = hasBattery,
        batteryPercent = batteryPercent,
        externalPowerConnected = externalPower,
    )
}

internal fun parseNmcliConnectivityProbe(output: String?): Boolean? {
    val states = output.orEmpty().lineSequence()
        .mapNotNull { line ->
            line.takeIf { it.startsWith("GENERAL.STATE:") }
                ?.substringAfter(':')
                ?.substringBefore(' ')
                ?.toIntOrNull()
        }
        .toList()
    if (states.isEmpty()) return null
    return states.any { it == NM_DEVICE_STATE_ACTIVATED }
}

internal fun parseNmcliMeteredProbe(output: String?): Boolean? {
    val connectedCosts = output.orEmpty().trim().split(Regex("\\n\\s*\\n"))
        .mapNotNull { block ->
            val fields = block.lineSequence().associate { line ->
                line.substringBefore(':') to line.substringAfter(':', "")
            }
            val state = fields["GENERAL.STATE"]?.substringBefore(' ')?.toIntOrNull()
            fields["GENERAL.METERED"]?.substringBefore(' ')?.takeIf { state == 100 }
        }
    if (connectedCosts.isEmpty()) return null
    if (connectedCosts.any { it == "yes" || it == "guess-yes" }) return false
    return true.takeIf { connectedCosts.all { it == "no" || it == "guess-no" } }
}

private fun runDesktopProbe(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    process.inputStream.bufferedReader().use { it.readText().take(MAX_PROBE_OUTPUT_CHARS) }
}.getOrNull()

private fun File.readProbeText(): String? = runCatching {
    takeIf(File::isFile)?.readText()?.trim()?.take(MAX_PROBE_OUTPUT_CHARS)
}.getOrNull()

private const val MAX_PROBE_OUTPUT_CHARS = 16_384
private const val NM_DEVICE_STATE_ACTIVATED = 100

internal interface DesktopKernelPowerApi : StdCallLibrary {
    fun GetSystemPowerStatus(status: DesktopSystemPowerStatus): Int
}

@Structure.FieldOrder(
    "ACLineStatus",
    "BatteryFlag",
    "BatteryLifePercent",
    "SystemStatusFlag",
    "BatteryLifeTime",
    "BatteryFullLifeTime",
)
internal class DesktopSystemPowerStatus : Structure() {
    @JvmField var ACLineStatus: Byte = 0
    @JvmField var BatteryFlag: Byte = 0
    @JvmField var BatteryLifePercent: Byte = 0
    @JvmField var SystemStatusFlag: Byte = 0
    @JvmField var BatteryLifeTime: Int = 0
    @JvmField var BatteryFullLifeTime: Int = 0
}
