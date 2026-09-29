package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedFilesTestWriteAuthorizationTest {
    private val origin = "https://cloud.example.test/nextcloud"
    private val prefix = "/remote.php/dav/files/nc-native-e2e/NC%20Native%20E2E"
    private val folder = "$origin$prefix"
    private val scope = assertNotNull(ScopedTestWriteAuthorization.create(origin, prefix))

    @Test
    fun `only explicit file descendants accept supported writes`() {
        listOf("PUT", "DELETE", "MKCOL").forEach { method ->
            assertTrue(scope.allows(method, "$folder/nested/file%20name.txt"), method)
            assertFalse(scope.allows(method, folder), method)
            assertFalse(scope.allows(method, "$folder/"), method)
        }
        assertTrue(scope.allows("MKCOL", "$folder/new-folder/"))
        listOf("POST", "PATCH", "LOCK", "UNLOCK", "GET").forEach { method ->
            assertFalse(scope.allows(method, "$folder/file.txt"), method)
        }
    }

    @Test
    fun `move and copy require both exact origin descendant targets`() {
        listOf("MOVE", "COPY").forEach { method ->
            assertTrue(scope.allows(method, "$folder/from.txt", "$folder/nested/to%20file.txt"))
            assertFalse(scope.allows(method, "$folder/from.txt"))
            rejectedTargets().forEach { target ->
                assertFalse(scope.allows(method, target, "$folder/to.txt"), "$method source $target")
                assertFalse(scope.allows(method, "$folder/from.txt", target), "$method destination $target")
            }
        }
    }

    @Test
    fun `file targets reject traversal encodings aliases and other origins`() {
        rejectedTargets().forEach { target ->
            assertFalse(scope.allows("PUT", target), target)
        }
    }

    @Test
    fun `only a single explicit folder can be authorized`() {
        listOf(
            "/remote.php/dav/files", "/remote.php/dav/files/nc-native-e2e",
            "$prefix/nested", "$prefix/..", "$prefix?query=1",
            prefix.replace("%20", "%2520"), prefix.replace("%20", "%2f"),
            "/remote.php/dav/files/nc-native-e2e/..",
        ).forEach { invalid ->
            assertNull(ScopedTestWriteAuthorization.create(origin, invalid), invalid)
        }
        assertNull(ScopedTestWriteAuthorization.create("http://cloud.example.test", prefix))
    }

    @Test
    fun `existing DAV and app scopes reject literal traversal and collection deletion`() {
        val davPrefix = "/remote.php/dav/calendars/nc-native-e2e/tasks"
        val dav = assertNotNull(ScopedTestWriteAuthorization.create(origin, davPrefix))
        assertFalse(dav.allows("DELETE", "$origin$davPrefix/"))
        assertFalse(dav.allows("PUT", "$origin$davPrefix/../other/item.ics"))
        val appPrefix = "/apps/example/api/records"
        val app = assertNotNull(ScopedTestWriteAuthorization.create(origin, appPrefix))
        assertFalse(app.allows("POST", "$origin$appPrefix/../other"))
        assertFalse(app.allows("MOVE", "$origin$appPrefix/1", "$origin$appPrefix/2"))
        assertTrue(app.allows("POST", "$origin$appPrefix?format=json"))
    }

    private fun rejectedTargets(): List<String> = listOf(
        folder, "$folder/", "$folder/../outside.txt", "$folder/./file.txt",
        "$folder/nested/../../outside.txt", "$folder//file.txt", "$folder/\\file.txt",
        "$folder/%2e%2e/outside.txt", "$folder/%2Foutside.txt", "$folder/%5cfile.txt",
        "$folder/%252e%252e/file.txt", "$folder/name%00.txt", "$folder/file.txt?target=elsewhere",
        "$folder/file.txt#fragment", "$folder/file;alias.txt", "$folder-other/file.txt",
        "$origin/remote.php/dav/files/nc-native-e2e/outside.txt",
        "$origin/remote.php/dav/uploads/nc-native-e2e/upload/file.txt",
        folder.replace("https://", "http://") + "/file.txt",
        folder.replace("cloud.example.test", "other.example.test") + "/file.txt",
        folder.replace("cloud.example.test", "cloud.example.test:444") + "/file.txt",
        folder.replace("https://", "https://user@") + "/file.txt",
        "/remote.php/dav/files/nc-native-e2e/NC%20Native%20E2E/file.txt",
    )
}
