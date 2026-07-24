package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class StaticSettingsMutationContractTest {
    @Test
    fun `paired singleton settings accept verified post put and patch writes`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                            ['name' => 'config#read', 'url' => '/api/config', 'verb' => 'GET'],
                            ['name' => 'config#post', 'url' => '/api/config', 'verb' => 'POST'],
                            ['name' => 'config#replace', 'url' => '/api/config', 'verb' => 'PUT'],
                            ['name' => 'config#patch', 'url' => '/api/config', 'verb' => 'PATCH'],
                            ['name' => 'config#delete', 'url' => '/api/config', 'verb' => 'DELETE'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/ConfigController.php" to """
                        <?php class ConfigController extends ApiController {
                            public function read() {}
                            public function post() {}
                            public function replace() {}
                            public function patch() {}
                            public function delete() {}
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val route = JSONObject(contract.document).getJSONObject("paths")
            .getJSONObject("/apps/example/api/config")
        assertTrue(route.has("get"))
        assertObservedSettingsBody(route.getJSONObject("post"))
        assertObservedSettingsBody(route.getJSONObject("put"))
        assertObservedSettingsBody(route.getJSONObject("patch"))
        assertFalse(route.getJSONObject("delete").has("requestBody"))
    }

    @Test
    fun `typed descendant setters support put and patch but exclude sensitive fields`() {
        val contract = assertNotNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                            ['name' => 'settings#read', 'url' => '/api/settings', 'verb' => 'GET'],
                            ['name' => 'settings#update_interval', 'url' => '/api/settings/interval', 'verb' => 'PUT'],
                            ['name' => 'settings#enable_notifications', 'url' => '/api/settings/notifications', 'verb' => 'PATCH'],
                            ['name' => 'settings#set_password', 'url' => '/api/settings/password', 'verb' => 'PATCH'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/SettingsController.php" to """
                        <?php class SettingsController extends ApiController {
                            public function read() {}
                            public function updateInterval(int ${'$'}value) {}
                            public function enableNotifications(bool ${'$'}value) {}
                            public function setPassword(string ${'$'}value) {}
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val paths = JSONObject(contract.document).getJSONObject("paths")
        val interval = paths.getJSONObject("/apps/example/api/settings/interval").getJSONObject("put")
        val notifications = paths.getJSONObject("/apps/example/api/settings/notifications").getJSONObject("patch")

        assertEquals("settings", interval.getString("x-nextcloud-native-resource-id"))
        assertEquals(
            "integer",
            interval.bodySchema().getJSONObject("properties").getJSONObject("interval").getString("type"),
        )
        assertEquals(
            "boolean",
            notifications.bodySchema().getJSONObject("properties").getJSONObject("notifications").getString("type"),
        )
        assertFalse(paths.has("/apps/example/api/settings/password"))
    }

    @Test
    fun `unpaired write route never becomes an open observed settings form`() {
        assertNull(
            synthesizeReadOnlyRouteContract(
                appId = "example",
                appVersion = "1.0.0",
                files = mapOf(
                    "example/appinfo/routes.php" to """
                        <?php return ['routes' => [
                            ['name' => 'config#replace', 'url' => '/api/config', 'verb' => 'PUT'],
                        ]];
                    """.trimIndent().encodeToByteArray(),
                    "example/lib/Controller/ConfigController.php" to """
                        <?php class ConfigController extends ApiController { public function replace() {} }
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )
    }

    private fun assertObservedSettingsBody(operation: JSONObject) {
        assertTrue(
            operation.bodySchema().getBoolean("x-nextcloud-native-observed-settings-body"),
        )
    }

    private fun JSONObject.bodySchema(): JSONObject =
        getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
}
