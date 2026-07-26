package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSharingTest {
    @Test
    fun capabilitiesUseOnlyAdvertisedShareSurfaces() {
        val capabilities = parseNextcloudFileSharingCapabilities(
            """
            {
              "ocs": {
                "data": {
                  "capabilities": {
                    "files_sharing": {
                      "api_enabled": true,
                      "default_permissions": 31,
                      "public": {"enabled": true, "multiple_links": true},
                      "user": {"expire_date": {"enabled": false}},
                      "group": {"enabled": true},
                      "group_sharing": true,
                      "sharebymail": {"enabled": true},
                      "federation": {"outgoing": true}
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertTrue(capabilities.apiEnabled)
        assertTrue(capabilities.publicLinks)
        assertTrue(capabilities.userShares)
        assertTrue(capabilities.groupShares)
        assertTrue(capabilities.emailRecipientQuery)
        assertTrue(capabilities.emailProviderAdvertised)
        assertTrue(capabilities.emailShares)
        assertTrue(capabilities.remoteShares)
        assertEquals(31, capabilities.defaultPermissions)
        assertTrue(capabilities.supportsAnyCreation)
    }

    @Test
    fun disabledOrMalformedCapabilitiesNeverGuessShareSupport() {
        assertEquals(
            NextcloudFileSharingCapabilities.Unavailable,
            parseNextcloudFileSharingCapabilities("""{"files_sharing":{"api_enabled":false}}"""),
        )
        assertEquals(
            NextcloudFileSharingCapabilities.Unavailable,
            parseNextcloudFileSharingCapabilities("not-json"),
        )
        val noUserContract = parseNextcloudFileSharingCapabilities(
            """{"files_sharing":{"api_enabled":true,"public":{"enabled":false}}}""",
        )
        assertFalse(noUserContract.userShares)
        assertFalse(noUserContract.publicLinks)
        assertTrue(noUserContract.emailRecipientQuery)
        assertFalse(noUserContract.emailProviderAdvertised)
        assertFalse(noUserContract.emailProviderObserved)
        assertFalse(noUserContract.supports(FileShareTarget.Email))
    }

    @Test
    fun listRequestIsAReadOnlyBoundedOcsQueryForOnePath() {
        val request = ListFileSharesRequest("Photos/Summer trip.jpg").toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/ocs/v2.php/apps/files_sharing/api/v1/shares", request.relativePath)
        assertEquals(
            mapOf(
                "format" to "json",
                "path" to "/Photos/Summer trip.jpg",
                "reshares" to "true",
            ),
            request.queryParameters,
        )
        assertTrue(request.ocsApiRequest)
        assertEquals(512L * 1024L, request.maximumResponseBytes)
        assertNull(request.body)
    }

    @Test
    fun recipientSearchUsesBoundedSameInstanceShareeDiscovery() {
        val request = SearchFileShareRecipientsRequest(
            query = " Ada ",
            target = FileShareTarget.User,
            limit = 20,
        ).toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/ocs/v2.php/apps/files_sharing/api/v1/sharees", request.relativePath)
        assertEquals(
            mapOf(
                "format" to "json",
                "search" to "Ada",
                "itemType" to "file",
                "shareType" to "0",
                "lookup" to "false",
                "perPage" to "20",
            ),
            request.queryParameters,
        )
        assertTrue(request.ocsApiRequest)
        assertEquals(512L * 1024L, request.maximumResponseBytes)
        assertNull(request.body)
    }

    @Test
    fun recipientSearchUsesSelectedFolderContextAndRequestedRemoteType() {
        val request = SearchFileShareRecipientsRequest(
            query = "person",
            target = FileShareTarget.Remote,
            itemType = FileShareItemType.Folder,
        ).toNextcloudApiRequest()

        assertEquals("folder", request.queryParameters["itemType"])
        assertEquals(FileShareTarget.Remote.wireValue.toString(), request.queryParameters["shareType"])
    }

    @Test
    fun recipientSearchParsesExactAndRegularUsersWithoutDuplicates() {
        val recipients = parseFileShareRecipientsResponse(
            NextcloudApiResponse(
                status = 200,
                body = """
                    {
                      "ocs": {
                        "meta": {"statuscode": 100},
                        "data": {
                          "exact": {
                            "users": [
                              {"label":"Ada Lovelace","value":{"shareType":0,"shareWith":"ada"}}
                            ],
                            "groups": []
                          },
                          "users": [
                            {"label":"Ada Lovelace","value":{"shareType":0,"shareWith":"ada"}},
                            {"label":"Adam Example","value":{"shareType":"0","shareWith":"adam"}}
                          ],
                          "groups": [
                            {"label":"Administrators","value":{"shareType":1,"shareWith":"admin"}}
                          ]
                        }
                      }
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = null,
            ),
            FileShareTarget.User,
        )

        assertEquals(2, recipients.size)
        assertEquals(FileShareRecipient("ada", "Ada Lovelace", FileShareTarget.User, exact = true), recipients[0])
        assertEquals("adam", recipients[1].id)
        assertFalse(recipients[1].exact)
    }

    @Test
    fun recipientSearchParsesEmailAndRemoteProvidersWithoutCrossTypeResults() {
        val response = NextcloudApiResponse(
            status = 200,
            body = """
                {
                  "ocs": {
                    "data": {
                      "exact": {
                        "emails": [
                          {"label":"Person","value":{"shareType":4,"shareWith":"person@example.test"}}
                        ],
                        "remotes": [
                          {"label":"Remote Person","value":{"shareType":6,"shareWith":"person@cloud.example.test"}}
                        ]
                      },
                      "emails": [
                        {"label":"Another Person","value":{"shareType":"4","shareWith":"other@example.test"}},
                        {"label":"Wrong type","value":{"shareType":0,"shareWith":"local-person"}}
                      ],
                      "remotes": []
                    }
                  }
                }
            """.trimIndent().encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )

        val emails = parseFileShareRecipientsResponse(response, FileShareTarget.Email)
        val remotes = parseFileShareRecipientsResponse(response, FileShareTarget.Remote)

        assertEquals(listOf("person@example.test", "other@example.test"), emails.map(FileShareRecipient::id))
        assertTrue(emails.first().exact)
        assertEquals(listOf("person@cloud.example.test"), remotes.map(FileShareRecipient::id))
        assertTrue(remotes.first().exact)
    }

    @Test
    fun emailProviderBecomesAvailableOnlyAfterAdvertisementOrObservedResults() {
        val queryOnly = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            publicLinks = true,
            emailRecipientQuery = true,
        )
        assertFalse(queryOnly.supports(FileShareTarget.Email))

        val observed = queryOnly.withObservedRecipientProvider(
            FileShareTarget.Email,
            listOf(
                FileShareRecipient(
                    id = "person@example.test",
                    displayName = "Person",
                    target = FileShareTarget.Email,
                ),
            ),
        )
        assertTrue(observed.emailProviderObserved)
        assertTrue(observed.supports(FileShareTarget.Email))
    }

    @Test
    fun recipientSearchFiltersResultsToRequestedShareType() {
        val response = NextcloudApiResponse(
            status = 200,
            body = """
                {
                  "ocs": {
                    "data": {
                      "exact_groups": [
                        {"label":"Design team","value":{"shareType":1,"shareWith":"design"}}
                      ],
                      "groups": [
                        {"label":"Developers","value":{"shareType":1,"shareWith":"devs"}},
                        {"label":"Wrong type","value":{"shareType":0,"shareWith":"user"}}
                      ]
                    }
                  }
                }
            """.trimIndent().encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )

        val groups = parseFileShareRecipientsResponse(response, FileShareTarget.Group)
        assertEquals(listOf("design", "devs"), groups.map(FileShareRecipient::id))
        assertTrue(groups.first().exact)
    }

    @Test
    fun recipientSearchRejectsEnumerationAndPublicLinkMisuse() {
        assertTrue(
            runCatching {
                SearchFileShareRecipientsRequest("a", FileShareTarget.User).toNextcloudApiRequest()
            }.isFailure,
        )
        assertTrue(
            runCatching {
                SearchFileShareRecipientsRequest("ada", FileShareTarget.PublicLink).toNextcloudApiRequest()
            }.isFailure,
        )
    }

    @Test
    fun existingSharePermissionUpdateIsABoundedOcsMutation() {
        val request = UpdateFileSharePermissionsRequest(
            shareId = "share-42",
            permissions = FileSharePermissions(read = true, update = true, reshare = true),
        ).toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/ocs/v2.php/apps/files_sharing/api/v1/shares/share-42", request.relativePath)
        assertEquals(mapOf("format" to "json"), request.queryParameters)
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", request.contentType)
        assertEquals("permissions=19", request.body?.decodeToString())
        assertTrue(request.ocsApiRequest)
        assertEquals(256L * 1024L, request.maximumResponseBytes)
    }

    @Test
    fun existingShareRevocationIsExplicitAndRejectsUnsafeIdentifiers() {
        val request = RevokeFileShareRequest("42").toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.DELETE, request.method)
        assertEquals("/ocs/v2.php/apps/files_sharing/api/v1/shares/42", request.relativePath)
        assertEquals(mapOf("format" to "json"), request.queryParameters)
        assertNull(request.body)
        assertTrue(request.ocsApiRequest)
        assertTrue(runCatching { RevokeFileShareRequest("../42").toNextcloudApiRequest() }.isFailure)
        assertTrue(runCatching { RevokeFileShareRequest("42/other").toNextcloudApiRequest() }.isFailure)
    }

    @Test
    fun existingSharePermissionMasksRoundTripIntoNativeLabels() {
        assertEquals(
            FileSharePermissions(read = true, update = true, reshare = true),
            fileSharePermissionsFromMask(19),
        )
        assertEquals("View · Edit · Reshare", fileSharePermissionsLabel(19))
        assertEquals("View · Edit · Create · Delete · Reshare", fileSharePermissionsLabel(31))
        assertEquals("Permissions set by server", fileSharePermissionsLabel(null))
    }

    @Test
    fun existingSharesPreserveUsefulNativeLabelsAndLinkData() {
        val shares = parseNextcloudFileSharesResponse(
            NextcloudApiResponse(
                status = 200,
                body = """
                    {
                      "ocs": {
                        "meta": {"statuscode": 100},
                        "data": [
                          {
                            "id": "41",
                            "share_type": 0,
                            "share_with": "ada",
                            "share_with_displayname": "Ada Lovelace",
                            "permissions": 19
                          },
                          {
                            "id": "42",
                            "share_type": 3,
                            "token": "public-token",
                            "url": "https://cloud.test/s/public-token",
                            "permissions": 1
                          }
                        ]
                      }
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = null,
            ),
        )

        assertEquals(2, shares.size)
        assertEquals("ada", shares[0].shareWith)
        assertEquals("Ada Lovelace", shares[0].displayName)
        assertEquals(19, shares[0].permissions)
        assertEquals("https://cloud.test/s/public-token", shares[1].url)
    }

    @Test
    fun creationPlanHonorsApiTargetAndDavSharePermissionForFilesAndFolders() {
        val capabilities = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            publicLinks = true,
            userShares = true,
        )
        val folder = file(path = "Projects", isDirectory = true, permissions = "RDNVW")
        val ready = planFileShareCreation(
            folder,
            FileShareTarget.PublicLink,
            recipient = null,
            permissions = FileSharePermissions(read = true, update = true, create = true, delete = true),
            capabilities = capabilities,
        )
        assertIs<FileShareCreationPlan.Ready>(ready)

        val notShareable = folder.copy(permissions = "DNVW")
        assertTrue(
            assertIs<FileShareCreationPlan.Blocked>(
                planFileShareCreation(
                    notShareable,
                    FileShareTarget.PublicLink,
                    null,
                    FileSharePermissions(),
                    capabilities,
                ),
            ).reason.contains("permission"),
        )
        assertTrue(
            assertIs<FileShareCreationPlan.Blocked>(
                planFileShareCreation(
                    folder,
                    FileShareTarget.Group,
                    "team",
                    FileSharePermissions(),
                    capabilities,
                ),
            ).reason.contains("groups"),
        )
    }

    @Test
    fun userSharePlanRequiresRecipientButUnknownDavPermissionsRemainApiAuthoritative() {
        val capabilities = NextcloudFileSharingCapabilities(apiEnabled = true, userShares = true)
        val file = file(path = "Notes/todo.md", permissions = null)

        assertIs<FileShareCreationPlan.Ready>(
            planFileShareCreation(
                file,
                FileShareTarget.User,
                "ada",
                FileSharePermissions(),
                capabilities,
            ),
        )
        assertIs<FileShareCreationPlan.Blocked>(
            planFileShareCreation(
                file,
                FileShareTarget.User,
                " ",
                FileSharePermissions(),
                capabilities,
            ),
        )
    }

    @Test
    fun emailAndRemoteSharePlansUseVerifiedProvidersAndAdvertisedCapabilities() {
        val capabilities = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            emailProviderObserved = true,
            remoteShares = true,
        )
        val file = file(path = "Notes/todo.md", permissions = null)

        val email = assertIs<FileShareCreationPlan.Ready>(
            planFileShareCreation(
                file,
                FileShareTarget.Email,
                "person@example.test",
                FileSharePermissionPreset.View.toPermissions(sourceIsDirectory = false),
                capabilities,
            ),
        )
        val remote = assertIs<FileShareCreationPlan.Ready>(
            planFileShareCreation(
                file,
                FileShareTarget.Remote,
                "person@cloud.example.test",
                FileSharePermissionPreset.Edit.toPermissions(sourceIsDirectory = false),
                capabilities,
            ),
        )

        assertTrue(email.request.toNextcloudApiRequest().body!!.decodeToString().contains("shareType=4"))
        assertTrue(remote.request.toNextcloudApiRequest().body!!.decodeToString().contains("shareType=6"))
    }

    @Test
    fun onlySameInstanceCredentialFreeShareLinksCanReachClipboard() {
        val session = NextcloudSession("https://cloud.test/nextcloud", "ada", "secret")
        assertEquals(
            "https://cloud.test/nextcloud/s/abc",
            safeFileShareUrl(
                session,
                NextcloudFileShare("1", "https://cloud.test/nextcloud/s/abc", "abc", 3),
            ),
        )
        assertNull(
            safeFileShareUrl(
                session,
                NextcloudFileShare("2", "https://cloud.test.evil/nextcloud/s/abc", "abc", 3),
            ),
        )
        assertNull(
            safeFileShareUrl(
                session,
                NextcloudFileShare("3", "https://user@cloud.test/nextcloud/s/abc", "abc", 3),
            ),
        )
        assertNull(
            safeFileShareUrl(
                session,
                NextcloudFileShare("4", "https://cloud.test/other/s/abc", "abc", 3),
            ),
        )
    }

    private fun file(
        path: String,
        isDirectory: Boolean = false,
        permissions: String?,
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        mimeType = if (isDirectory) null else "text/plain",
        size = if (isDirectory) null else 10,
        lastModified = null,
        fileId = 42,
        hasPreview = false,
        etag = "\"v1\"",
        permissions = permissions,
    )
}
