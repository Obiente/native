package dev.obiente.nextcloudnative.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSessionPublicationGuardTest {
    @Test
    fun replacementCannotPublishBeforeAnOlderLoadFinishes() {
        val guard = DesktopSessionPublicationGuard()
        val loadEntered = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val loadThread = Thread {
            guard.serialize {
                events += "read-account-old"
                loadEntered.countDown()
                check(allowLoadToFinish.await(5, TimeUnit.SECONDS))
                events += "publish-account-old"
            }
        }
        loadThread.start()
        assertTrue(loadEntered.await(5, TimeUnit.SECONDS))

        val replacementThread = Thread {
            guard.serialize {
                events += "save-account-new"
                events += "publish-account-new"
            }
        }
        replacementThread.start()
        replacementThread.join(100)
        assertTrue(replacementThread.isAlive)

        allowLoadToFinish.countDown()
        loadThread.join(5_000)
        replacementThread.join(5_000)

        assertFalse(loadThread.isAlive)
        assertFalse(replacementThread.isAlive)
        assertEquals(
            listOf(
                "read-account-old",
                "publish-account-old",
                "save-account-new",
                "publish-account-new",
            ),
            events,
        )
    }
}
