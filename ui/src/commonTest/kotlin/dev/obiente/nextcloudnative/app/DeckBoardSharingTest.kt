package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeckBoardSharingTest {
    private val routes = DeckWriteRoutePlan(DeckApiVersion("1.1"))
    private val alice = DeckAcl(
        id = 31,
        boardId = 7,
        type = DeckAclType.User,
        participant = DeckAclParticipant("alice", "Alice Example"),
        canEdit = true,
        canShare = false,
        canManage = false,
    )

    @Test
    fun `board record exposes typed acl and settings`() {
        val board = parseDeckBoard(
            deckToolResponse(
                """{"id":7,"title":"Plan","color":"0082c9","owner":{"uid":"owner","displayname":"Owner"},
                "permissions":{"PERMISSION_READ":true,"PERMISSION_EDIT":true,"PERMISSION_MANAGE":true,"PERMISSION_SHARE":true},
                "acl":[{"id":31,"boardId":7,"type":0,"participant":{"uid":"alice","displayname":"Alice Example","type":0},
                "permissionEdit":true,"permissionShare":false,"permissionManage":false,"owner":false},
                {"id":32,"boardId":7,"type":1,"participant":{"uid":"staff","displayname":"Staff","type":1},
                "permissionEdit":false,"permissionShare":true,"permissionManage":false},
                {"id":33,"boardId":7,"type":7,"participant":{"uid":"team-1","displayname":"Team","type":7},
                "permissionEdit":"1","permissionShare":false,"permissionManage":true}],
                "settings":{"notify-due":"all","calendar":""}}""",
            ),
        )

        assertTrue(board.aclReadable)
        assertEquals(listOf(DeckAclType.User, DeckAclType.Group, DeckAclType.Circle), board.acl.map(DeckAcl::type))
        assertEquals("Alice Example", board.acl.first().participant.displayName)
        assertTrue(board.acl[2].canEdit && board.acl[2].canManage)
        assertEquals(DeckDueNotification.All, board.settings?.dueNotification)
        assertEquals(false, board.settings?.showInCalendar)
    }

    @Test
    fun `malformed acl keeps the board readable but sharing read only`() {
        val board = parseDeckBoard(
            deckToolResponse(
                """{"id":7,"title":"Plan","permissions":{"PERMISSION_SHARE":true,"PERMISSION_MANAGE":true},
                "acl":[{"id":31,"boardId":99,"type":0,"participant":"alice"}]}""",
            ),
        )

        assertFalse(board.aclReadable)
        assertTrue(board.acl.isEmpty())
        assertFalse(board.canOpenSharing())
        assertFalse(board.canAddShares())
    }

    @Test
    fun `adding a share posts the participant with no extra permissions`() {
        val request = routes.addBoardAcl(board(), DeckSharee("staff", "Staff", DeckAclType.Group))
        val body = request.jsonBody()

        assertEquals(NextcloudApiMethod.POST, request.method)
        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/acl", request.relativePath)
        assertEquals(1, body.getValue("type").jsonPrimitive.int)
        assertEquals("staff", body.getValue("participant").jsonPrimitive.content)
        assertFalse(body.getValue("permissionEdit").jsonPrimitive.boolean)
        assertFalse(body.getValue("permissionManage").jsonPrimitive.boolean)
    }

    @Test
    fun `adding needs share permission and rejects duplicates and the owner`() {
        val noShare = board(permissions = permissions(share = false))
        assertFailsWith<IllegalArgumentException> {
            routes.addBoardAcl(noShare, DeckSharee("bob", "Bob", DeckAclType.User))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.addBoardAcl(board(), DeckSharee("alice", "Alice", DeckAclType.User))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.addBoardAcl(board(), DeckSharee("owner", "Owner", DeckAclType.User))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.addBoardAcl(board(archived = true), DeckSharee("bob", "Bob", DeckAclType.User))
        }
        assertFailsWith<IllegalArgumentException> { DeckSharee("remote@x", "Remote", DeckAclType.Remote) }
    }

    @Test
    fun `updating binds the acl to its board and respects permission caps`() {
        val request = routes.updateBoardAcl(
            board(),
            alice,
            currentUserId = "owner",
            requested = DeckAclPermissions(canEdit = false, canShare = true, canManage = false),
        )
        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/acl/31", request.relativePath)
        assertTrue(request.jsonBody().getValue("permissionShare").jsonPrimitive.boolean)

        val sharer = board(permissions = permissions(manage = false))
        assertFailsWith<IllegalArgumentException> {
            routes.updateBoardAcl(sharer, alice, "owner", DeckAclPermissions(true, false, true))
        }
        assertFailsWith<IllegalStateException> {
            routes.updateBoardAcl(board(), alice.copy(id = 99), "owner", alice.permissions())
        }
        assertFailsWith<IllegalArgumentException> {
            routes.updateBoardAcl(board(), alice.copy(boardId = 8), "owner", alice.permissions())
        }
    }

    @Test
    fun `own share is never editable and removal needs manage permission`() {
        val own = board().aclEditability(alice, currentUserId = "alice")
        assertFalse(own.canChangeAny || own.canRemove)
        val sharer = board(permissions = permissions(manage = false)).aclEditability(alice, "bob")
        assertTrue(sharer.canChangeEdit && sharer.canChangeShare)
        assertFalse(sharer.canChangeManage || sharer.canRemove)
        assertFailsWith<IllegalArgumentException> {
            routes.deleteBoardAcl(board(permissions = permissions(manage = false)), alice, "bob")
        }
        val delete = routes.deleteBoardAcl(board(), alice, "owner")
        assertEquals(NextcloudApiMethod.DELETE, delete.method)
        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/acl/31", delete.relativePath)
    }

    @Test
    fun `sharee search uses core autocomplete without remote shares`() {
        val request = deckShareeSearchRequest(7, "  ali ")
        assertEquals("/ocs/v2.php/core/autocomplete/get", request.relativePath)
        assertEquals("ali", request.queryParameters["search"])
        assertEquals("deck", request.queryParameters["itemType"])
        assertEquals(setOf("0", "1", "7"), request.queryParameters.filterKeys { it.startsWith("shareTypes") }.values.toSet())
        assertTrue(request.ocsApiRequest)
        assertFailsWith<IllegalArgumentException> { deckShareeSearchRequest(7, "a\u0000b") }
        assertFailsWith<IllegalArgumentException> { deckShareeSearchRequest(0, "a") }
    }

    @Test
    fun `sharee parsing keeps supported sources and drops malformed entries`() {
        val sharees = parseDeckSharees(
            deckToolResponse(
                """{"ocs":{"meta":{"statuscode":200},"data":[
                {"id":"alice","label":"Alice","source":"users","subline":"Design"},
                {"id":"staff","label":"Staff","source":"groups"},
                {"id":"t1","label":"Team","source":"circles"},
                {"id":"r@x","label":"Remote","source":"remotes"},
                {"label":"No id","source":"users"},
                {"id":"alice","label":"Alice again","source":"users"}]}}""",
            ),
        )
        assertEquals(listOf("alice", "staff", "t1"), sharees.map(DeckSharee::id))
        assertEquals("Design", sharees.first().detail)
        assertFailsWith<IllegalArgumentException> { parseDeckSharees(deckToolResponse("{}", status = 403)) }
        assertFailsWith<IllegalStateException> { parseDeckSharees(deckToolResponse("not json")) }
        assertFailsWith<IllegalArgumentException> {
            parseDeckSharees(deckToolResponse("""{"ocs":{"meta":{"statuscode":403},"data":[]}}"""))
        }
    }

    @Test
    fun `postconditions compare the refreshed board with the request`() {
        val requested = DeckAclPermissions(canEdit = true, canShare = true, canManage = false)
        assertIs<DeckWriteVerification.Confirmed>(
            verifyDeckAclUpdated(board(acl = listOf(alice.copy(canShare = true))), alice.id, requested),
        )
        assertIs<DeckWriteVerification.Adjusted>(verifyDeckAclUpdated(board(), alice.id, requested))
        assertIs<DeckWriteVerification.NotApplied>(verifyDeckAclUpdated(board(acl = emptyList()), alice.id, requested))
        assertIs<DeckWriteVerification.Confirmed>(verifyDeckAclRemoved(board(acl = emptyList()), alice.id))
        assertIs<DeckWriteVerification.NotApplied>(verifyDeckAclRemoved(board(), alice.id))
        assertIs<DeckWriteVerification.NotApplied>(
            verifyDeckAclAdded(board(), DeckSharee("bob", "Bob", DeckAclType.User)),
        )
    }

    private fun board(
        archived: Boolean = false,
        permissions: DeckPermissions = permissions(),
        acl: List<DeckAcl> = listOf(alice),
    ) = DeckBoard(
        id = 7,
        title = "Plan",
        color = "0082c9",
        archived = archived,
        owner = DeckUser("owner", "Owner"),
        labels = emptyList(),
        permissions = permissions,
        shared = true,
        lastModified = 1L,
        etag = "e1",
        acl = acl,
    )

    private fun permissions(edit: Boolean = true, share: Boolean = true, manage: Boolean = true) =
        DeckPermissions(canRead = true, canEdit = edit, canManage = manage, canShare = share)

    private fun NextcloudApiRequest.jsonBody(): JsonObject =
        Json.parseToJsonElement(checkNotNull(body).decodeToString()) as JsonObject
}

internal fun deckToolResponse(json: String, status: Int = 200) = NextcloudApiResponse(
    status = status,
    body = json.encodeToByteArray(),
    contentType = "application/json",
    etag = null,
)
