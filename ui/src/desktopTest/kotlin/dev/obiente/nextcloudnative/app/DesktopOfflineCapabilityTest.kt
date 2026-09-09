package dev.obiente.nextcloudnative.app

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOfflineCapabilityTest {
    @Test
    fun `linux advertises recursive folder availability without individual pinning`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        DesktopNextcloudServices().use { services ->
            assertFalse(services.supportsFileOfflineStorage)
            assertTrue(services.supportsRecursiveFileOfflineStorage)
        }
    }
}
