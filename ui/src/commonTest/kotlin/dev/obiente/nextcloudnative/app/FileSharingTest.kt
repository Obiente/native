package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
                      "public": {
                        "enabled": true,
                        "multiple_links": true,
                        "password": {"enforced": true},
                        "expire_date": {"enabled": true, "enforced": false}
                      },
                      "user": {"expire_date": {"enabled": false}},
                      "group": {"enabled": true, "expire_date": {"enabled": true}},
                      "group_sharing": true,
                      "sharebymail": {
                        "enabled": true,
                        "password": {"enabled": true, "enforced": false},
                        "expire_date": {"enabled": true, "enforced": true}
                      },
                      "federation": {
                        "outgoing": true,
                        "expire_date_supported": {"enabled": true}
                      }
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
        assertEquals(FileShareFeaturePolicy(true, true), capabilities.passwordPolicy(FileShareTarget.PublicLink))
        assertEquals(FileShareFeaturePolicy(true, false), capabilities.passwordPolicy(FileShareTarget.Email))
        assertEquals(FileShareFeaturePolicy(true, true), capabilities.expirationPolicy(FileShareTarget.Email))
        assertEquals(FileShareFeaturePolicy(true, false), capabilities.expirationPolicy(FileShareTarget.Remote))
        assertTrue(capabilities.canOffer(FileShareTarget.Email))
        assertEquals(31, capabilities.defaultPermissions)
        assertTrue(capabilities.supportsAnyCreation)
    }

    @Test
    fun federatedExpirationAcceptsDocumentedObjectAndLegacyBooleanShapes() {
        fun parseCapability(value: String): NextcloudFileSharingCapabilities =
            parseNextcloudFileSharingCapabilities(
                """
                {
                  "files_sharing": {
                    "api_enabled": true,
                    "federation": {
                      "outgoing": true,
                      "expire_date_supported": $value
                    }
                  }
                }
                """.trimIndent(),
            )

        assertTrue(parseCapability("""{"enabled": true}""").remoteExpirationSupported)
        assertTrue(parseCapability("true").remoteExpirationSupported)
        assertFalse(parseCapability("""{"enabled": false}""").remoteExpirationSupported)
        assertFalse(parseCapability("false").remoteExpirationSupported)
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
    fun disabledPublicExpirationCapabilityIsNotOffered() {
        val capabilities = parseNextcloudFileSharingCapabilities(
            """
            {
              "files_sharing": {
                "api_enabled": true,
                "public": {
                  "enabled": true,
                  "expire_date": {"enabled": false, "enforced": true}
                }
              }
            }
            """.trimIndent(),
        )

        assertFalse(capabilities.publicExpirationSupported)
        assertEquals(
            FileShareFeaturePolicy(supported = false, enforced = false),
            capabilities.expirationPolicy(FileShareTarget.PublicLink),
        )
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
    fun syntheticRecipientFixturesCoverEveryParsedShareeType() {
        val response = NextcloudApiResponse(
            status = 200,
            body = """
                {
                  "ocs": {
                    "data": {
                      "exact": {
                        "users": [
                          {"label":"Synthetic user","value":{"shareType":0,"shareWith":"synthetic-user"}}
                        ],
                        "groups": [
                          {"label":"Synthetic group","value":{"shareType":1,"shareWith":"synthetic-group"}}
                        ],
                        "emails": [
                          {"label":"Synthetic email","value":{"shareType":4,"shareWith":"person@example.test"}}
                        ],
                        "remotes": [
                          {"label":"Synthetic remote","value":{"shareType":6,"shareWith":"person@cloud.example.test"}}
                        ]
                      }
                    }
                  }
                }
            """.trimIndent().encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )

        listOf(
            FileShareTarget.User to "synthetic-user",
            FileShareTarget.Group to "synthetic-group",
            FileShareTarget.Email to "person@example.test",
            FileShareTarget.Remote to "person@cloud.example.test",
        ).forEach { (target, expectedId) ->
            val recipients = parseFileShareRecipientsResponse(response, target)
            assertEquals(listOf(expectedId), recipients.map(FileShareRecipient::id))
            assertTrue(recipients.all { it.target == target && it.exact })
        }
    }

    @Test
    fun observedEmailRecipientsRemainDiscoveryEvidenceNotWriteAuthorization() {
        val queryOnly = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            publicLinks = true,
            emailRecipientQuery = true,
        )
        assertFalse(queryOnly.supports(FileShareTarget.Email))
        assertTrue(queryOnly.canOffer(FileShareTarget.Email))

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
        assertFalse(observed.supports(FileShareTarget.Email))
        assertTrue(observed.canOffer(FileShareTarget.Email))

        val advertised = observed.copy(emailProviderAdvertised = true)
        assertTrue(advertised.supports(FileShareTarget.Email))
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
    fun existingLinkSettingsUpdatePreservesClearVersusUnchangedSemantics() {
        val request = UpdateFileShareRequest(
            shareId = "share-42",
            target = FileShareTarget.PublicLink,
            permissions = FileSharePermissions(read = true),
            password = "",
            expirationDate = "2028-02-29",
            note = "Synthetic handoff\r\nSecond paragraph",
        ).toNextcloudApiRequest()

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals(
            "permissions=1&password=&expireDate=2028-02-29&" +
                "note=Synthetic%20handoff%0D%0ASecond%20paragraph",
            request.body?.decodeToString(),
        )
        assertTrue(
            runCatching {
                UpdateFileShareRequest(
                    shareId = "share-42",
                    target = FileShareTarget.User,
                    password = "not-allowed",
                ).toNextcloudApiRequest()
            }.isFailure,
        )
        assertTrue(
            runCatching {
                UpdateFileShareRequest(
                    shareId = "share-42",
                    target = FileShareTarget.PublicLink,
                    note = "Unsafe\u0007note",
                ).toNextcloudApiRequest()
            }.isFailure,
        )
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
    fun nonPermissionEditsPreserveCustomAndMissingPermissionMasks() {
        val customMaskShare = NextcloudFileShare(
            id = "share-custom",
            url = null,
            token = null,
            shareType = FileShareTarget.User.wireValue,
            permissions = 5,
            note = "Original note",
        )
        val customUpdate = assertNotNull(
            planExistingFileShareUpdate(
                share = customMaskShare,
                draft = existingFileShareEditDraft(customMaskShare).copy(note = "Updated note"),
                sourceIsDirectory = true,
                target = FileShareTarget.User,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )
        assertNull(customUpdate.permissions)
        assertEquals(
            "note=Updated%20note",
            customUpdate.toNextcloudApiRequest().body?.decodeToString(),
        )

        val missingMaskShare = customMaskShare.copy(
            id = "share-server-policy",
            permissions = null,
            note = null,
        )
        val missingMaskUpdate = assertNotNull(
            planExistingFileShareUpdate(
                share = missingMaskShare,
                draft = existingFileShareEditDraft(missingMaskShare).copy(note = "Synthetic note"),
                sourceIsDirectory = false,
                target = FileShareTarget.User,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )
        assertNull(missingMaskUpdate.permissions)
    }

    @Test
    fun permissionControlsOnlySendAMaskAfterAnExplicitChange() {
        val share = NextcloudFileShare(
            id = "share-42",
            url = null,
            token = null,
            shareType = FileShareTarget.Group.wireValue,
            permissions = 31,
        )
        assertNull(
            planExistingFileShareUpdate(
                share = share,
                draft = existingFileShareEditDraft(share),
                sourceIsDirectory = true,
                target = FileShareTarget.Group,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )

        val update = assertNotNull(
            planExistingFileShareUpdate(
                share = share,
                draft = existingFileShareEditDraft(share).copy(allowResharing = false),
                sourceIsDirectory = true,
                target = FileShareTarget.Group,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )
        assertEquals(15, update.permissions?.mask)

        val customShare = share.copy(permissions = 5)
        val reshareEnabled = assertNotNull(
            planExistingFileShareUpdate(
                share = customShare,
                draft = existingFileShareEditDraft(customShare).copy(allowResharing = true),
                sourceIsDirectory = true,
                target = FileShareTarget.Group,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )
        assertEquals(21, reshareEnabled.permissions?.mask)

        val reshareDisabled = assertNotNull(
            planExistingFileShareUpdate(
                share = customShare.copy(permissions = 21),
                draft = existingFileShareEditDraft(customShare.copy(permissions = 21))
                    .copy(allowResharing = false),
                sourceIsDirectory = true,
                target = FileShareTarget.Group,
                expirationPolicy = FileShareFeaturePolicy(supported = false),
            ),
        )
        assertEquals(5, reshareDisabled.permissions?.mask)
    }

    @Test
    fun editDraftResetReconcilesEveryFieldFromAuthoritativeShareState() {
        val changed = NextcloudFileShare(
            id = "share-42",
            url = null,
            token = null,
            shareType = FileShareTarget.PublicLink.wireValue,
            permissions = 19,
            expiration = "2028-02-29",
            note = "Server-confirmed note",
            passwordProtected = true,
        )

        assertEquals(
            ExistingFileShareEditDraft(
                allowEditing = true,
                allowResharing = true,
                newPassword = "",
                removePassword = false,
                expirationDate = "2028-02-29",
                note = "Server-confirmed note",
            ),
            existingFileShareEditDraft(changed),
        )
    }

    @Test
    fun existingShareUpdateLeavesTimezoneRelativeExpirationAuthorityToServer() {
        val update = UpdateFileShareRequest(
            shareId = "share-42",
            target = FileShareTarget.PublicLink,
            expirationDate = "2020-01-01",
        )

        assertEquals(
            "expireDate=2020-01-01",
            update.toNextcloudApiRequest().body?.decodeToString(),
        )
        assertTrue(
            runCatching {
                update.copy(expirationDate = "2020-02-30").toNextcloudApiRequest()
            }.isFailure,
        )
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
                            "permissions": 19,
                            "expiration": "2028-02-29 00:00:00",
                            "note": "Synthetic handoff\nSecond paragraph",
                            "password": "redacted"
                          },
                          {
                            "id": "42",
                            "share_type": 3,
                            "token": "public-token",
                            "url": "https://cloud.test/s/public-token",
                            "permissions": 1,
                            "note": "Unsafe\u0007note"
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
        assertEquals("2028-02-29", shares[0].expiration)
        assertEquals("Synthetic handoff\nSecond paragraph", shares[0].note)
        assertTrue(shares[0].passwordProtected)
        assertEquals("https://cloud.test/s/public-token", shares[1].url)
        assertNull(shares[1].note)
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
    fun creationPlanEnforcesAdvertisedPasswordAndExpirationPolicies() {
        val capabilities = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            publicLinks = true,
            publicPasswordSupported = true,
            publicPasswordEnforced = true,
            publicExpirationSupported = true,
            publicExpirationEnforced = true,
        )
        val file = file(path = "Documents/Guide.pdf", permissions = null)

        val missingPassword = assertIs<FileShareCreationPlan.Blocked>(
            planFileShareCreation(
                file,
                FileShareTarget.PublicLink,
                recipient = null,
                permissions = FileSharePermissions(),
                capabilities = capabilities,
            ),
        )
        assertTrue(missingPassword.reason.contains("requires a password"))

        val forbiddenNoExpiration = assertIs<FileShareCreationPlan.Blocked>(
            planFileShareCreation(
                file,
                FileShareTarget.PublicLink,
                recipient = null,
                permissions = FileSharePermissions(),
                capabilities = capabilities,
                details = FileShareCreationDetails(
                    password = "synthetic-password",
                    expiration = FileShareExpiration.NoExpiration,
                ),
            ),
        )
        assertTrue(forbiddenNoExpiration.reason.contains("requires an expiration date"))

        assertIs<FileShareCreationPlan.Ready>(
            planFileShareCreation(
                file,
                FileShareTarget.PublicLink,
                recipient = null,
                permissions = FileSharePermissions(),
                capabilities = capabilities,
                details = FileShareCreationDetails(
                    password = "synthetic-password",
                    expiration = FileShareExpiration.OnDate("2028-02-29"),
                    note = "Synthetic project handoff",
                ),
            ),
        )
    }

    @Test
    fun emailAndRemoteSharePlansUseVerifiedProvidersAndAdvertisedCapabilities() {
        val capabilities = NextcloudFileSharingCapabilities(
            apiEnabled = true,
            emailProviderAdvertised = true,
            remoteShares = true,
        )
        val file = file(path = "Notes/todo.md", permissions = null)

        val observedOnly = capabilities.copy(
            emailProviderAdvertised = false,
            emailProviderObserved = true,
            emailRecipientQuery = true,
        )
        assertIs<FileShareCreationPlan.Blocked>(
            planFileShareCreation(
                file,
                FileShareTarget.Email,
                "person@example.test",
                FileSharePermissionPreset.View.toPermissions(sourceIsDirectory = false),
                observedOnly,
            ),
        )

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
