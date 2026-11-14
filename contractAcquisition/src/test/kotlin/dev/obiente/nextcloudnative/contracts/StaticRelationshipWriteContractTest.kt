package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * Relationship (`assign`, `unassign`, `add`, `remove`) and restore (`restore`, `undoDelete`) verbs
 * are accepted for every signed app whose scalar signature proves the related identity. Sources are
 * synthetic and shaped like a Kanban-style package; no app identifier is special.
 */
class StaticRelationshipWriteContractTest {
    @Test
    fun `scalar relationship and restore signatures become verified writes for any app`() {
        val paths = paths(contract("kanban_x", "1.15.0", ROUTES, CONTROLLERS))

        val assignLabel = paths.operation("/apps/kanban_x/cards/{cardId}/label/{labelId}", "post")
        assertTrue(assignLabel.getBoolean(VERIFIED_CRUD_EXTENSION))
        assertFalse(assignLabel.has("requestBody"))
        val removeLabel = paths.operation("/apps/kanban_x/cards/{cardId}/label/{labelId}", "delete")
        assertTrue(removeLabel.getBoolean(VERIFIED_CRUD_EXTENSION))
        assertFalse(removeLabel.has("requestBody"))

        val assignUser = paths.operation("/apps/kanban_x/cards/{cardId}/assign", "post")
        assertEquals("cards", assignUser.getString("x-nextcloud-native-resource-id"))
        assertEquals(setOf("userId", "type"), assignUser.bodyProperties())
        assertEquals(setOf("userId"), assignUser.requiredBodyProperties())
        val unassignUser = paths.operation("/apps/kanban_x/cards/{cardId}/unassign", "put")
        assertEquals(setOf("userId", "type"), unassignUser.bodyProperties())

        val restoreBoard = paths.operation("/apps/kanban_x/boards/{boardId}/deleteUndo", "post")
        assertEquals("boards", restoreBoard.getString("x-nextcloud-native-resource-id"))
        assertFalse(restoreBoard.has("requestBody"))
        val restoreAttachment = paths.operation("/apps/kanban_x/cards/{cardId}/attachment/{attachmentId}/restore", "put")
        assertTrue(restoreAttachment.getBoolean(VERIFIED_CRUD_EXTENSION))
    }

    @Test
    fun `the contract is stamped with the exact package version it was synthesized from`() {
        val older = contract("kanban_x", "1.15.0", ROUTES, CONTROLLERS)
        val newer = contract("kanban_x", "1.16.0", ROUTES, CONTROLLERS)

        assertEquals("1.15.0", older.appVersion)
        assertEquals("1.15.0", JSONObject(older.document).getJSONObject("info").getString("version"))
        assertEquals("1.16.0", JSONObject(newer.document).getJSONObject("info").getString("version"))
    }

    @Test
    fun `sharing, membership, send, and merge verbs stay outside the relationship allowlist`() {
        val paths = paths(
            contract(
                "kanban_x",
                "1.15.0",
                """
                  ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                  ['name' => 'board#addAcl', 'url' => '/boards/{boardId}/acl', 'verb' => 'POST'],
                  ['name' => 'board#addMember', 'url' => '/boards/{boardId}/members', 'verb' => 'POST'],
                  ['name' => 'board#removeGroup', 'url' => '/boards/{boardId}/groups/{groupId}', 'verb' => 'DELETE'],
                  ['name' => 'board#addShare', 'url' => '/boards/{boardId}/shares', 'verb' => 'POST'],
                  ['name' => 'card#sendCard', 'url' => '/cards/{cardId}/send', 'verb' => 'POST'],
                  ['name' => 'card#mergeCard', 'url' => '/cards/{cardId}/merge', 'verb' => 'POST'],
                """,
                """
                  public function read(int ${'$'}cardId): array { return []; }
                  public function addAcl(int ${'$'}boardId, int ${'$'}aclId, string ${'$'}participant): array { return []; }
                  public function addMember(int ${'$'}boardId, string ${'$'}memberId): array { return []; }
                  public function removeGroup(int ${'$'}boardId, string ${'$'}groupId): array { return []; }
                  public function addShare(int ${'$'}boardId, int ${'$'}shareId): array { return []; }
                  public function sendCard(int ${'$'}cardId, string ${'$'}recipientId): array { return []; }
                  public function mergeCard(int ${'$'}cardId, int ${'$'}otherCardId): array { return []; }
                """,
            ),
        )

        assertEquals(setOf("/apps/kanban_x/cards/{cardId}"), paths.keySet())
    }

    @Test
    fun `a relationship name without the related identity or with request input is not proven`() {
        val paths = paths(
            contract(
                "kanban_x",
                "1.15.0",
                """
                  ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                  ['name' => 'card#assignLabel', 'url' => '/cards/{cardId}/assignLabel', 'verb' => 'PUT'],
                  ['name' => 'card#removeLabel', 'url' => '/cards/{cardId}/removeLabel', 'verb' => 'PUT'],
                  ['name' => 'card#assignUser', 'url' => '/cards/{cardId}/assignUser', 'verb' => 'PUT'],
                  ['name' => 'card#addTag', 'url' => '/cards/{cardId}/tags', 'verb' => 'DELETE'],
                  ['name' => 'card#restore', 'url' => '/cards/restore', 'verb' => 'POST'],
                """,
                """
                  public function read(int ${'$'}cardId): array { return []; }
                  public function assignLabel(string ${'$'}name): array { return []; }
                  public function removeLabel(${'$'}labelId): array { return ${'$'}this->service->remove(${'$'}this->request->getParam('cardId'), ${'$'}labelId); }
                  public function assignUser(int ${'$'}cardId, Request ${'$'}user): array { return []; }
                  public function addTag(int ${'$'}cardId, int ${'$'}tagId): array { return []; }
                  public function restore(int ${'$'}cardId): array { return []; }
                """,
            ),
        )

        assertEquals(setOf("/apps/kanban_x/cards/{cardId}"), paths.keySet())
    }

    @Test
    fun `a relationship signature carrying a sensitive field is rejected`() {
        val paths = paths(
            contract(
                "kanban_x",
                "1.15.0",
                """
                  ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                  ['name' => 'card#assignUser', 'url' => '/cards/{cardId}/assign', 'verb' => 'POST'],
                """,
                """
                  public function read(int ${'$'}cardId): array { return []; }
                  public function assignUser(int ${'$'}cardId, string ${'$'}userId, string ${'$'}password): array { return []; }
                """,
            ),
        )

        assertFalse(paths.has("/apps/kanban_x/cards/{cardId}/assign"))
    }

    @Test
    fun `a malformed controller signature yields no relationship write`() {
        val synthesized = synthesize(
                "kanban_x",
                "1.15.0",
                """
                  ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
                  ['name' => 'card#assignLabel', 'url' => '/cards/{cardId}/label/{labelId}', 'verb' => 'POST'],
                """,
                """
                  public function read(int ${'$'}cardId): array { return []; }
                  public function assignLabel(int ${'$'}cardId, int ${'$'}labelId: array { return []; }
                """,
        )

        // A controller that cannot be parsed contributes nothing, so no write can be published.
        assertNull(synthesized?.let(::paths)?.optJSONObject("/apps/kanban_x/cards/{cardId}/label/{labelId}"))
    }

    private fun contract(appId: String, version: String, routes: String, methods: String): VerifiedPackageContract =
        assertNotNull(synthesize(appId, version, routes, methods))

    private fun synthesize(appId: String, version: String, routes: String, methods: String): VerifiedPackageContract? =
            synthesizeReadOnlyRouteContract(
                appId = appId,
                appVersion = version,
                files = mapOf(
                    "$appId/appinfo/routes.php" to "<?php return ['routes' => [\n$routes\n]];".encodeToByteArray(),
                    "$appId/lib/Controller/CardController.php" to """
                        <?php
                        use OCP\AppFramework\ApiController;
                        class CardController extends ApiController {
                        $methods
                        }
                    """.trimIndent().encodeToByteArray(),
                ),
            )

    private fun paths(contract: VerifiedPackageContract): JSONObject = JSONObject(contract.document).getJSONObject("paths")

    private fun JSONObject.operation(path: String, verb: String): JSONObject =
        assertNotNull(optJSONObject(path)?.optJSONObject(verb), "missing $verb $path in ${keySet()}")

    private fun JSONObject.bodySchema(): JSONObject = getJSONObject("requestBody").getJSONObject("content")
        .getJSONObject("application/json").getJSONObject("schema")

    private fun JSONObject.bodyProperties(): Set<String> = bodySchema().getJSONObject("properties").keySet()

    private fun JSONObject.requiredBodyProperties(): Set<String> =
        bodySchema().optJSONArray("required")?.toList()?.map(Any::toString)?.toSet().orEmpty()

    private companion object {
        val ROUTES = """
          ['name' => 'card#read', 'url' => '/cards/{cardId}', 'verb' => 'GET'],
          ['name' => 'card#assignLabel', 'url' => '/cards/{cardId}/label/{labelId}', 'verb' => 'POST'],
          ['name' => 'card#removeLabel', 'url' => '/cards/{cardId}/label/{labelId}', 'verb' => 'DELETE'],
          ['name' => 'card#assignUser', 'url' => '/cards/{cardId}/assign', 'verb' => 'POST'],
          ['name' => 'card#unassignUser', 'url' => '/cards/{cardId}/unassign', 'verb' => 'PUT'],
          ['name' => 'card#deleteUndo', 'url' => '/boards/{boardId}/deleteUndo', 'verb' => 'POST'],
          ['name' => 'card#restore', 'url' => '/cards/{cardId}/attachment/{attachmentId}/restore', 'verb' => 'PUT'],
        """
        val CONTROLLERS = """
          public function read(int ${'$'}cardId): array { return []; }
          public function assignLabel(int ${'$'}cardId, int ${'$'}labelId): array { return []; }
          public function removeLabel(int ${'$'}cardId, int ${'$'}labelId): array { return []; }
          public function assignUser(int ${'$'}cardId, string ${'$'}userId, int ${'$'}type = 0): array { return []; }
          public function unassignUser(int ${'$'}cardId, string ${'$'}userId, int ${'$'}type = 0): array { return []; }
          public function deleteUndo(int ${'$'}boardId): array { return []; }
          public function restore(int ${'$'}cardId, int ${'$'}attachmentId): array { return []; }
        """
    }
}
