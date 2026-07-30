package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicCollectionDestinationSelectionTest {
    @Test
    fun `process restoration reuses last known contract identity in read only mode`() {
        val plan = planDynamicContractResume(
            liveServerVersion = null,
            lastKnownServerVersion = "32.0.5",
            lastKnownInstalledAppVersion = "0.23.0",
        )

        assertEquals("32.0.5", plan.serverVersion)
        assertEquals("0.23.0", plan.installedAppVersionHint)
        assertFalse(plan.serverVersionVerified)
    }

    @Test
    fun `live server identity supersedes the restored version and enables verification`() {
        val plan = planDynamicContractResume(
            liveServerVersion = "33.0.0",
            lastKnownServerVersion = "32.0.5",
            lastKnownInstalledAppVersion = "0.23.0",
        )

        assertEquals("33.0.0", plan.serverVersion)
        assertEquals("0.23.0", plan.installedAppVersionHint)
        assertTrue(plan.serverVersionVerified)
    }

    @Test
    fun `switching a root collection clears stale hierarchy context`() {
        val mutableParameters = mutableMapOf("houseId" to "house-2")

        val plan = planDynamicCollectionDestinationSelection(
            isTopLevelDestination = true,
            destinationPathParameterValues = mutableParameters,
        )
        mutableParameters["houseId"] = "stale"

        assertTrue(plan.clearHierarchyContext)
        assertEquals(mapOf("houseId" to "house-2"), plan.pathParameterValues)
    }

    @Test
    fun `switching a contextual collection preserves its selected parent`() {
        val plan = planDynamicCollectionDestinationSelection(
            isTopLevelDestination = false,
            destinationPathParameterValues = mapOf(
                "houseId" to "house-2",
                "checklistId" to "checklist-9",
            ),
        )

        assertFalse(plan.clearHierarchyContext)
        assertEquals(
            mapOf(
                "houseId" to "house-2",
                "checklistId" to "checklist-9",
            ),
            plan.pathParameterValues,
        )
    }
}
