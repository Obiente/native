package dev.obiente.nextcloudnative

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLifecycleSmokeTest {
    @Test
    fun appSurvivesRecreationAndRotationWithoutLeakingInternalFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            assertAppSurfaceVisible(device)

            scenario.recreate()
            assertAppSurfaceVisible(device)

            device.setOrientationLeft()
            device.waitForIdle()
            assertAppSurfaceVisible(device)

            val hierarchy = ByteArrayOutputStream().use { output ->
                device.dumpWindowHierarchy(output)
                output.toString(Charsets.UTF_8.name())
            }
            assertFalse(
                "Coroutine cancellation must never be rendered as a user-facing failure.",
                hierarchy.contains("The coroutine scope left the composition"),
            )
            assertFalse(
                "Internal null failures must never be rendered as a user-facing failure.",
                hierarchy.contains("Required value was null"),
            )
        } finally {
            device.setOrientationNatural()
            scenario.close()
        }
    }

    private fun assertAppSurfaceVisible(device: UiDevice) {
        val appSurface = device.wait(
            Until.findObject(By.pkg("dev.obiente.nextcloudnative").depth(0)),
            10_000,
        )
        assertNotNull("nati.ve did not expose a window within 10 seconds.", appSurface)
    }
}
