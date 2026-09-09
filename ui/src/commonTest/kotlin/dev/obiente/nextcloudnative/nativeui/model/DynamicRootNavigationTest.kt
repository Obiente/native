package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicRootNavigationTest {
    @Test
    fun `parameterless singleton detail becomes an app root without a resource name allowlist`() {
        val dashboard = action("read-dashboard", "dashboard")
        val descriptor = descriptor(dashboard)

        assertEquals(
            listOf("read-dashboard"),
            descriptor.planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::actionId),
        )
    }

    @Test
    fun `singleton detail requiring query input does not become an app root`() {
        val lookup = action("read-report", "report").copy(
            binding = action("read-report", "report").binding.copy(
                queryParameters = listOf(
                    HttpParameter(
                        name = "reportId",
                        required = true,
                        schema = buildJsonObject {},
                        source = ParameterSource.userInput,
                    ),
                ),
            ),
        )

        assertTrue(descriptor(lookup).planDynamicNavigation().rootDestinations.isEmpty())
    }

    @Test
    fun `command-like GET does not become an automatic app root`() {
        val clearCache = action("clear-cache", "cache").copy(
            binding = action("clear-cache", "cache").binding.copy(path = "/cache/clear"),
        )

        assertTrue(descriptor(clearCache).planDynamicNavigation().rootDestinations.isEmpty())
    }

    @Test
    fun `read beneath a command-named collection remains an app root`() {
        val history = action("read-run-history", "run-history").copy(
            label = "Run history",
            binding = action("read-run-history", "run-history").binding.copy(path = "/runs/history"),
        )

        assertEquals(
            listOf("read-run-history"),
            descriptor(history).planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::actionId),
        )
    }

    private fun descriptor(action: DynamicAction): DynamicAppDescriptor = DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = AppIdentity("example", "Example", "test"),
        endpointPolicy = EndpointPolicy("https://cloud.example.test"),
        resources = listOf(
            DynamicResource(
                id = action.resourceId,
                label = action.resourceId,
                collection = false,
                confidence = Confidence.high,
            ),
        ),
        layouts = listOf(
            DynamicLayout(
                id = "${action.resourceId}.detail",
                title = action.resourceId,
                resourceId = action.resourceId,
                kind = LayoutKind.detail,
                sourceActionId = action.id,
                confidence = Confidence.high,
            ),
        ),
        actions = listOf(action),
    )

    private fun action(id: String, resourceId: String): DynamicAction = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(
            method = HttpMethod.GET,
            path = "/$resourceId",
        ),
        confidence = Confidence.high,
    )
}
