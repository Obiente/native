package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopExternalUrlLauncherTest {
    @Test
    fun supportedAwtBrowseIsPreferred() {
        val opened = mutableListOf<URI>()
        val commands = mutableListOf<List<String>>()
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = opened::add,
            startCommand = { arguments -> commands += arguments; acceptedProcess() },
        )

        val method = launcher.open("https://cloud.example.test/index.php/login/v2/flow/abc")

        assertEquals(DesktopExternalUrlMethod.Awt, method)
        assertEquals(listOf(URI("https://cloud.example.test/index.php/login/v2/flow/abc")), opened)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun linuxUsesXdgOpenWhenAwtBrowseIsUnavailable() {
        val commands = mutableListOf<List<String>>()
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = null,
            startCommand = { arguments -> commands += arguments; acceptedProcess() },
        )

        val method = launcher.open("https://cloud.example.test/index.php/login/v2/flow/abc?token=redacted")

        assertEquals(DesktopExternalUrlMethod.LinuxXdgOpen, method)
        assertEquals(
            listOf(
                listOf(
                    "xdg-open",
                    "https://cloud.example.test/index.php/login/v2/flow/abc?token=redacted",
                ),
            ),
            commands,
        )
    }

    @Test
    fun linuxFallsBackToGioWhenXdgOpenCannotStart() {
        val commands = mutableListOf<List<String>>()
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = { throw UnsupportedOperationException("BROWSE is unavailable") },
            startCommand = { arguments ->
                commands += arguments
                if (arguments.first() == "xdg-open") throw IOException("missing helper")
                acceptedProcess()
            },
        )

        val method = launcher.open("https://cloud.example.test/login")

        assertEquals(DesktopExternalUrlMethod.LinuxGio, method)
        assertEquals(listOf("xdg-open", "gio"), commands.map(List<String>::first))
    }

    @Test
    fun failedLinuxHelpersProduceAnActionableSafeFailure() {
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = null,
            startCommand = { throw IOException("missing helper") },
        )

        val failure = assertFailsWith<DesktopExternalUrlLaunchException> {
            launcher.open("https://cloud.example.test/login?token=private")
        }

        assertEquals("BROWSER_HANDOFF_UNAVAILABLE", failure.code)
        assertEquals(
            listOf(DesktopExternalUrlMethod.LinuxXdgOpen, DesktopExternalUrlMethod.LinuxGio),
            failure.attemptedMethods,
        )
        assertTrue(failure.message.orEmpty().contains("default browser"))
        assertTrue(failure.message.orEmpty().contains("xdg-utils"))
        assertTrue(!failure.message.orEmpty().contains("cloud.example.test"))
        assertTrue(!failure.message.orEmpty().contains("private"))
    }

    @Test
    fun unsafeUrlsAreRejectedBeforeAPlatformProcessStarts() {
        var commandStarted = false
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = null,
            startCommand = { commandStarted = true; acceptedProcess() },
        )

        val failure = assertFailsWith<DesktopExternalUrlLaunchException> {
            launcher.open("file:///tmp/private-login-response")
        }

        assertEquals("BROWSER_URL_INVALID", failure.code)
        assertTrue(failure.attemptedMethods.isEmpty())
        assertTrue(!commandStarted)
    }

    @Test
    fun platformFallbacksPassTheUrlAsOneArgumentWithoutAShell() {
        val uri = URI("https://cloud.example.test/login?value=a%20b")

        assertEquals(
            listOf("open", uri.toASCIIString()),
            desktopExternalUrlCommands("Mac OS X", uri).single().arguments,
        )
        assertEquals(
            listOf("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()),
            desktopExternalUrlCommands("Windows 11", uri).single().arguments,
        )
    }

    @Test
    fun immediateNonzeroXdgExitFallsBackToGio() {
        val commands = mutableListOf<String>()
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = null,
            startCommand = { arguments ->
                commands += arguments.first()
                if (arguments.first() == "xdg-open") exitedProcess(3) else acceptedProcess()
            },
        )

        val method = launcher.open("https://cloud.example.test/login")

        assertEquals(DesktopExternalUrlMethod.LinuxGio, method)
        assertEquals(listOf("xdg-open", "gio"), commands)
    }

    @Test
    fun runningHelperCountsAsAcceptedWithoutWaitingForItToExit() {
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = null,
            startCommand = { DesktopExternalUrlProcess { null } },
        )

        assertEquals(
            DesktopExternalUrlMethod.LinuxXdgOpen,
            launcher.open("https://cloud.example.test/login"),
        )
    }

    @Test
    fun mailAndTelephoneLinksUseNativeHelpersWithoutAwtBrowse() {
        val commands = mutableListOf<List<String>>()
        val launcher = DesktopExternalUrlLauncher(
            osName = "Linux",
            awtBrowser = { error("AWT browse must not receive non-web schemes") },
            startCommand = { arguments -> commands += arguments; acceptedProcess() },
        )

        assertEquals(DesktopExternalUrlMethod.LinuxXdgOpen, launcher.open("mailto:person@example.test"))
        assertEquals(DesktopExternalUrlMethod.LinuxXdgOpen, launcher.open("tel:+31201234567"))
        assertEquals(
            listOf(
                listOf("xdg-open", "mailto:person@example.test"),
                listOf("xdg-open", "tel:+31201234567"),
            ),
            commands,
        )
    }

    @Test
    fun unsafeEmailAndTelephoneLinksAreRejected() {
        listOf(
            "mailto:person@example.test?subject=secret",
            "mailto:missing-at-sign",
            "tel:12;postd=34",
            "tel:+12",
        ).forEach { url ->
            val launcher = DesktopExternalUrlLauncher(
                osName = "Linux",
                awtBrowser = null,
                startCommand = { error("Unsafe link reached a platform helper") },
            )
            assertEquals(
                "BROWSER_URL_INVALID",
                assertFailsWith<DesktopExternalUrlLaunchException> { launcher.open(url) }.code,
            )
        }
    }

    @Test
    fun failureDiagnosticsContainOnlyStablePlatformMetadata() {
        val failure = DesktopExternalUrlLaunchException(
            message = "failure mentioning https://private.example.test/login?token=secret",
            code = "BROWSER_HANDOFF_UNAVAILABLE",
            attemptedMethods = listOf(
                DesktopExternalUrlMethod.Awt,
                DesktopExternalUrlMethod.LinuxXdgOpen,
                DesktopExternalUrlMethod.LinuxGio,
            ),
        )

        val event = desktopExternalUrlFailureDiagnostic(failure, osName = "Linux")

        assertEquals(SupportDiagnosticComponent.Platform, event.component)
        assertEquals("browser.open", event.operation)
        assertEquals("failed", event.outcome)
        assertEquals("BROWSER_HANDOFF_UNAVAILABLE", event.code)
        assertEquals(
            listOf(
                SupportDiagnosticFieldDraft("platform", "linux"),
                SupportDiagnosticFieldDraft("attempted_methods", "awt,xdg-open,gio"),
            ),
            event.fields,
        )
        assertEquals(null, event.message)
        assertEquals(null, event.exception)
    }

    private fun acceptedProcess(): DesktopExternalUrlProcess = exitedProcess(0)

    private fun exitedProcess(exitCode: Int): DesktopExternalUrlProcess =
        DesktopExternalUrlProcess { exitCode }
}
