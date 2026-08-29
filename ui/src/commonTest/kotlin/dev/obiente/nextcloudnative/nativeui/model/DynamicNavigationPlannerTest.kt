package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicNavigationPlannerTest {
    @Test
    fun `interactive autocomplete collection is a picker source rather than an app root`() {
        val autocomplete = action(
            id = "user-autocomplete",
            resourceId = "users",
            intent = ActionIntent.list,
        ).copy(
            label = "Autocomplete users",
            binding = DynamicHttpBinding(
                method = HttpMethod.GET,
                path = "/api/users/autocomplete",
                queryParameters = listOf(
                    HttpParameter(
                        name = "search",
                        required = false,
                        schema = buildJsonObject {},
                        source = ParameterSource.userInput,
                    ),
                    HttpParameter(
                        name = "limit",
                        required = false,
                        schema = buildJsonObject {},
                        source = ParameterSource.userInput,
                    ),
                ),
            ),
        )
        val ordinaryUsers = autocomplete.copy(
            id = "list-users",
            label = "Users",
            binding = autocomplete.binding.copy(path = "/api/users"),
        )
        val helperDescriptor = hierarchyDescriptor().copy(
            resources = listOf(resource("users")),
            layouts = listOf(layout("users", autocomplete.id)),
            actions = listOf(autocomplete),
            links = emptyList(),
            forms = emptyList(),
        )
        val ordinaryDescriptor = helperDescriptor.copy(
            layouts = listOf(layout("users", ordinaryUsers.id)),
            actions = listOf(ordinaryUsers),
        )

        assertTrue(helperDescriptor.planDynamicNavigation().rootDestinations.isEmpty())
        assertEquals(
            listOf("users"),
            ordinaryDescriptor.planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::resourceId),
        )
    }

    @Test
    fun `Cospend projects lead to bills and members with strict record context`() {
        val descriptor = hierarchyDescriptor()

        assertEquals(
            listOf("projects"),
            descriptor.planDynamicNavigation().rootDestinations.map(DynamicNavigationDestination::resourceId),
        )
        assertEquals(
            listOf("create-project.form"),
            descriptor.planDynamicNavigation().rootFormActions.map(DynamicNavigationFormAction::formId),
        )

        val plan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "projects",
                recordId = "record-project",
                parameterValues = mapOf("projectId" to "exact-project"),
            ),
        )

        assertEquals(
            listOf("bills", "members"),
            plan.contextualChildDestinations.map(DynamicNavigationDestination::resourceId),
        )
        assertEquals(
            listOf(mapOf("projectId" to "exact-project"), mapOf("projectId" to "exact-project")),
            plan.contextualChildDestinations.map(DynamicNavigationDestination::pathParameterValues),
        )
        assertEquals(
            listOf("create-bill.form", "edit-project.form"),
            plan.contextualFormActions.map(DynamicNavigationFormAction::formId),
        )
        assertFalse(plan.contextualFormActions.any { it.formId == "edit-bill.form" })
        assertFalse(plan.contextualFormActions.any { it.formId == "create-project.form" })
    }

    @Test
    fun `record id supplies only its matching resource identifier`() {
        val plan = hierarchyDescriptor().planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "projects", recordId = "project-7"),
        )

        assertEquals(
            setOf(mapOf("projectId" to "project-7")),
            plan.contextualChildDestinations.map(DynamicNavigationDestination::pathParameterValues).toSet(),
        )
        assertFalse(plan.contextualFormActions.any { it.actionId == "edit-bill" })
    }

    @Test
    fun `coincidental context field cannot authorize an unrelated mutation form`() {
        val updateProfile = action(
            "update-profile",
            "profiles",
            ActionIntent.update,
            "userId",
            method = HttpMethod.PATCH,
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("workspace", "Workspace", "test"),
            resources = listOf(resource("projects"), resource("profiles")),
            layouts = emptyList(),
            links = emptyList(),
            forms = listOf(
                form("update-profile.form", "Update profile", "profiles", updateProfile.id),
            ),
            actions = listOf(updateProfile),
        )

        val plan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "projects",
                recordId = "project-7",
                fieldValues = mapOf("userId" to "user-4"),
            ),
        )

        assertTrue(plan.contextualFormActions.isEmpty())
    }

    @Test
    fun `verified file upload form is contextual only with complete trusted route bindings`() {
        val listPhotos = action(
            "list-photos",
            "photos",
            ActionIntent.list,
            "houseId",
        ).copy(confidence = Confidence.verified)
        val uploadPhoto = action(
            "upload-photo",
            "photos",
            ActionIntent.execute,
            "houseId",
            method = HttpMethod.POST,
            body = HttpBody(
                contentType = "multipart/form-data",
                required = true,
                schema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "image",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("format", JsonPrimitive("binary"))
                                },
                            )
                        },
                    )
                },
            ),
        ).copy(
            effect = ActionEffect.upload,
            confidence = Confidence.verified,
            provenance = listOf(
                Provenance(
                    kind = ProvenanceKind.verifiedAppPackage,
                    source = "signed app package",
                    detail = "Verified multipart contract",
                ),
            ),
        )
        val uploadForm = form(
            id = "upload-photo.form",
            title = "Upload",
            resourceId = "photos",
            actionId = uploadPhoto.id,
        ).copy(
            fields = listOf(
                FormField(
                    fieldId = "image",
                    label = "Image",
                    kind = FieldKind.file,
                    required = true,
                ),
            ),
            confidence = Confidence.verified,
            provenance = uploadPhoto.provenance,
        )
        val photoLayout = layout("photos", listPhotos.id).copy(
            confidence = Confidence.verified,
        )
        val descriptor = hierarchyDescriptor().copy(
            resources = listOf(
                resource("houses").copy(confidence = Confidence.verified),
                resource("photos").copy(confidence = Confidence.verified),
            ),
            layouts = listOf(photoLayout),
            links = emptyList(),
            forms = listOf(uploadForm),
            actions = listOf(listPhotos, uploadPhoto),
        )
        val trustedContext = DynamicResourceRecordContext(
            resourceId = "houses",
            recordId = "house-7",
            parameterValues = mapOf("houseId" to "house-7"),
            currentLayoutId = photoLayout.id,
        )

        assertEquals(
            mapOf("houseId" to "house-7"),
            descriptor.planDynamicNavigation(trustedContext)
                .contextualFormActions
                .single { action -> action.actionId == uploadPhoto.id }
                .pathParameterValues,
        )
        assertTrue(
            descriptor.planDynamicNavigation(
                trustedContext.copy(
                    parameterValues = emptyMap(),
                    actionSafeIdentity = false,
                ),
            ).contextualFormActions.isEmpty(),
        )
        assertTrue(
            descriptor.copy(forms = listOf(uploadForm.copy(provenance = emptyList())))
                .planDynamicNavigation(trustedContext)
                .contextualFormActions
                .isEmpty(),
        )
    }

    @Test
    fun `cross-resource singleton update requires one verified active detail read surface`() {
        val readPreferences = action(
            "read-preferences",
            "preferences",
            ActionIntent.read,
            "houseId",
        ).copy(confidence = Confidence.verified)
        val updatePreferences = action(
            "update-preferences",
            "preferences",
            ActionIntent.update,
            "houseId",
            method = HttpMethod.PATCH,
        ).copy(confidence = Confidence.verified)
        val preferencesLayout = layout("preferences", readPreferences.id).copy(
            id = "preferences.detail",
            kind = LayoutKind.detail,
            confidence = Confidence.verified,
        )
        val preferencesForm = form(
            id = "update-preferences.form",
            title = "Preferences",
            resourceId = "preferences",
            actionId = updatePreferences.id,
        ).copy(
            fields = listOf(
                FormField(
                    fieldId = "enabled",
                    label = "Enabled",
                    kind = FieldKind.boolean,
                    required = true,
                ),
            ),
            confidence = Confidence.verified,
        )
        val descriptor = hierarchyDescriptor().copy(
            resources = listOf(
                resource("houses").copy(confidence = Confidence.verified),
                resource("preferences").copy(
                    collection = false,
                    confidence = Confidence.verified,
                ),
            ),
            layouts = listOf(preferencesLayout),
            links = emptyList(),
            forms = listOf(preferencesForm),
            actions = listOf(readPreferences, updatePreferences),
        )
        val activePreferences = DynamicResourceRecordContext(
            resourceId = "houses",
            recordId = "house-7",
            parameterValues = mapOf("houseId" to "house-7"),
            currentLayoutId = preferencesLayout.id,
        )

        assertEquals(
            listOf(updatePreferences.id),
            descriptor.planDynamicNavigation(activePreferences)
                .contextualFormActions
                .map(DynamicNavigationFormAction::actionId),
        )
        assertTrue(
            descriptor.copy(
                layouts = listOf(preferencesLayout.copy(kind = LayoutKind.list)),
            ).planDynamicNavigation(activePreferences)
                .contextualFormActions
                .isEmpty(),
        )
        assertTrue(
            descriptor.planDynamicNavigation(
                activePreferences.copy(
                    parameterValues = emptyMap(),
                    actionSafeIdentity = false,
                ),
            ).contextualFormActions.isEmpty(),
        )
    }

    @Test
    fun `ephemeral map identity can open read children but cannot authorize forms`() {
        val plan = hierarchyDescriptor().planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "projects",
                recordId = "observed-map-key",
                actionSafeIdentity = false,
            ),
        )

        assertEquals(
            setOf("bills", "members"),
            plan.contextualChildDestinations.map(DynamicNavigationDestination::resourceId).toSet(),
        )
        assertTrue(plan.contextualFormActions.isEmpty())
    }

    @Test
    fun `conflicting record provenance keeps read navigation but withholds every contextual write`() {
        val plan = hierarchyDescriptor().planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "projects",
                recordId = "project-7",
                parameterValues = mapOf("projectId" to "project-7"),
                actionSafeIdentity = true,
                actionBindingProvenanceValid = false,
            ),
        )

        assertEquals(
            setOf("bills", "members"),
            plan.contextualChildDestinations.mapTo(mutableSetOf()) { it.resourceId },
        )
        assertTrue(plan.contextualFormActions.isEmpty())
    }

    @Test
    fun `record capabilities gate same-record forms without blocking a verified child create`() {
        val deleteProject = action(
            "delete-project",
            "projects",
            ActionIntent.delete,
            "projectId",
            method = HttpMethod.DELETE,
        )
        val capabilityFields = listOf(
            "readOnly",
            "writable",
            "canWrite",
            "canEdit",
            "canUpdate",
            "canDelete",
        ).map { fieldId ->
            DynamicField(
                id = fieldId,
                label = fieldId,
                kind = FieldKind.boolean,
                required = false,
                readOnly = true,
                nullable = false,
                multiple = false,
                confidence = Confidence.high,
            )
        }
        val baseline = hierarchyDescriptor()
        val descriptor = baseline.copy(
            resources = baseline.resources.map { resource ->
                resource.takeUnless { it.id == "projects" }
                    ?: resource.copy(fields = capabilityFields)
            },
            forms = baseline.forms + form(
                "delete-project.form",
                "Delete project",
                "projects",
                deleteProject.id,
            ),
            actions = baseline.actions + deleteProject,
        )
        val baseContext = DynamicResourceRecordContext(
            resourceId = "projects",
            recordId = "project-7",
            parameterValues = mapOf("projectId" to "project-7"),
        )

        fun contextualFormIds(fieldValues: Map<String, String?>): Set<String> =
            descriptor.planDynamicNavigation(baseContext.copy(fieldValues = fieldValues))
                .contextualFormActions
                .mapTo(mutableSetOf(), DynamicNavigationFormAction::formId)

        val allowed = mapOf(
            "readOnly" to "false",
            "writable" to "true",
            "canWrite" to "true",
            "canEdit" to "true",
            "canUpdate" to "true",
            "canDelete" to "true",
        )
        assertEquals(
            setOf("create-bill.form", "delete-project.form", "edit-project.form"),
            contextualFormIds(allowed),
        )
        assertEquals(
            setOf("create-bill.form"),
            contextualFormIds(allowed + ("readOnly" to "true")),
        )
        assertEquals(
            setOf("create-bill.form"),
            contextualFormIds(
                allowed + mapOf(
                    "canEdit" to "false",
                    "canUpdate" to "false",
                    "canDelete" to "false",
                ),
            ),
        )
        assertEquals(
            setOf("create-bill.form"),
            contextualFormIds(emptyMap()),
        )

        fun descriptorWithScopedCapabilities(vararg fieldIds: String): DynamicAppDescriptor =
            descriptor.copy(
                resources = descriptor.resources.map { resource ->
                    resource.takeUnless { it.id == "projects" }
                        ?: resource.copy(
                            fields = capabilityFields.filter { field -> field.id in fieldIds },
                        )
                },
            )

        fun scopedContextualFormIds(
            scopedDescriptor: DynamicAppDescriptor,
            fieldValues: Map<String, String?>,
        ): Set<String> = scopedDescriptor
            .planDynamicNavigation(baseContext.copy(fieldValues = fieldValues))
            .contextualFormActions
            .mapTo(mutableSetOf(), DynamicNavigationFormAction::formId)

        val deleteOnly = descriptorWithScopedCapabilities("canDelete")
        assertEquals(
            setOf("create-bill.form", "delete-project.form"),
            scopedContextualFormIds(deleteOnly, mapOf("canDelete" to "true")),
        )

        val editOnly = descriptorWithScopedCapabilities("canEdit")
        assertEquals(
            setOf("create-bill.form", "edit-project.form"),
            scopedContextualFormIds(editOnly, mapOf("canEdit" to "true")),
        )

        val noScopedCapabilities = descriptorWithScopedCapabilities()
        assertEquals(
            setOf("create-bill.form", "delete-project.form", "edit-project.form"),
            scopedContextualFormIds(noScopedCapabilities, emptyMap()),
        )
    }

    @Test
    fun `declared child create can bind one required parent body field from record context`() {
        val listCollections = action("list-collections", "collections", ActionIntent.list)
        val listEntries = action("list-entries", "entries", ActionIntent.list, "collectionId")
        val createEntry = action(
            id = "create-entry",
            resourceId = "entries",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            body = HttpBody(
                contentType = "application/json",
                required = true,
                schema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("collectionId", buildJsonObject { put("type", "integer") })
                            put("title", buildJsonObject { put("type", "string") })
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("collectionId"))
                            add(JsonPrimitive("title"))
                        },
                    )
                },
            ),
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("shared-lists", "Shared lists", "test"),
            resources = listOf(resource("collections"), resource("entries")),
            layouts = listOf(
                layout("collections", listCollections.id),
                layout("entries", listEntries.id),
            ),
            links = listOf(
                actionLink("collections.entries", "Entries", "collections", listEntries.id),
            ),
            forms = listOf(
                DynamicForm(
                    id = "create-entry.form",
                    title = "Create entry",
                    resourceId = "entries",
                    actionId = createEntry.id,
                    fields = listOf(
                        FormField("collectionId", "Collection", FieldKind.integer, required = true),
                        FormField("title", "Title", FieldKind.string, required = true),
                    ),
                    confidence = Confidence.high,
                ),
            ),
            actions = listOf(listCollections, listEntries, createEntry),
        )

        val action = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "collections",
                recordId = "collection-7",
            ),
        ).contextualFormActions.single()

        assertEquals("create-entry.form", action.formId)
        assertEquals(mapOf("collectionId" to "collection-7"), action.pathParameterValues)
    }

    @Test
    fun `verified record execute binds declared body identity only after record selection`() {
        val listInvitations = action(
            id = "list-invitations",
            resourceId = "invites",
            intent = ActionIntent.list,
        ).copy(
            confidence = Confidence.verified,
            responseFieldIds = listOf("inviteId", "teamId", "teamName"),
        )
        val acceptInvitation = action(
            id = "accept-invitation",
            resourceId = "invitations",
            intent = ActionIntent.execute,
            method = HttpMethod.POST,
            body = HttpBody(
                contentType = "application/json",
                required = true,
                schema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("teamId", buildJsonObject { put("type", "integer") })
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("teamId")) })
                },
            ),
        ).copy(
            confidence = Confidence.verified,
            binding = action(
                id = "accept-invitation",
                resourceId = "invitations",
                intent = ActionIntent.execute,
                method = HttpMethod.POST,
                body = HttpBody(
                    contentType = "application/json",
                    required = true,
                    schema = buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put("teamId", buildJsonObject { put("type", "integer") })
                            },
                        )
                        put("required", buildJsonArray { add(JsonPrimitive("teamId")) })
                    },
                ),
            ).binding.copy(path = "/apps/chores/api/v1.0/account/invites/accept"),
            provenance = listOf(
                Provenance(
                    kind = ProvenanceKind.verifiedAppPackage,
                    source = "signed Chores 0.1.0 package",
                    detail = "Exact verified controller contract",
                ),
            ),
        )
        val invitationForm = DynamicForm(
            id = "accept-invitation.form",
            title = "Accept invitation",
            resourceId = "invitations",
            actionId = acceptInvitation.id,
            fields = listOf(
                FormField("teamId", "Team", FieldKind.integer, required = true),
            ),
            confidence = Confidence.verified,
        )
        val invitationLayout = layout("invites", listInvitations.id).copy(
            confidence = Confidence.verified,
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("chores", "Chores", "0.1.0"),
            resources = listOf(
                resource("invites").copy(confidence = Confidence.verified),
                resource("invitations").copy(confidence = Confidence.verified),
            ),
            layouts = listOf(invitationLayout),
            links = emptyList(),
            forms = listOf(invitationForm),
            actions = listOf(listInvitations, acceptInvitation),
        )

        assertTrue(descriptor.planDynamicNavigation().rootFormActions.isEmpty())
        assertTrue(
            descriptor.copy(app = AppIdentity("shared-work", "Shared work", "test"))
                .planDynamicNavigation(
                    DynamicResourceRecordContext(
                        resourceId = "invites",
                        recordId = "invite-7",
                        fieldValues = mapOf("teamId" to "42", "teamName" to "Home"),
                        currentLayoutId = invitationLayout.id,
                    ),
                ).contextualFormActions.isEmpty(),
        )
        val contextual = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "invites",
                recordId = "invite-7",
                fieldValues = mapOf("teamId" to "42", "teamName" to "Home"),
                currentLayoutId = invitationLayout.id,
            ),
        ).contextualFormActions.single()

        assertEquals(acceptInvitation.id, contextual.actionId)
        assertEquals(mapOf("teamId" to "42"), contextual.pathParameterValues)
        assertTrue(
            descriptor.copy(
                actions = listOf(
                    listInvitations.copy(responseFieldIds = listOf("inviteId", "teamName")),
                    acceptInvitation,
                ),
            ).planDynamicNavigation(
                DynamicResourceRecordContext(
                    resourceId = "invites",
                    recordId = "invite-7",
                    fieldValues = mapOf("teamId" to "42"),
                    currentLayoutId = invitationLayout.id,
                ),
            ).contextualFormActions.isEmpty(),
        )
    }

    @Test
    fun `body scoped child create is withheld without a declared relationship or safe parent identity`() {
        val listCollections = action("list-collections", "collections", ActionIntent.list)
        val listEntries = action("list-entries", "entries", ActionIntent.list, "collectionId")
        val createEntry = action(
            id = "create-entry",
            resourceId = "entries",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            body = HttpBody(
                contentType = "application/json",
                required = true,
                schema = buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put("collectionId", buildJsonObject { put("type", "integer") })
                        },
                    )
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("collectionId"))
                        },
                    )
                },
            ),
        )
        val createForm = DynamicForm(
            id = "create-entry.form",
            title = "Create entry",
            resourceId = "entries",
            actionId = createEntry.id,
            fields = listOf(
                FormField("collectionId", "Collection", FieldKind.integer, required = true),
            ),
            confidence = Confidence.high,
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("shared-lists", "Shared lists", "test"),
            resources = listOf(resource("collections"), resource("entries")),
            layouts = listOf(
                layout("collections", listCollections.id),
                layout("entries", listEntries.id),
            ),
            links = listOf(
                actionLink("collections.entries", "Entries", "collections", listEntries.id),
            ),
            forms = listOf(createForm),
            actions = listOf(listCollections, listEntries, createEntry),
        )
        val selectedCollection = DynamicResourceRecordContext(
            resourceId = "collections",
            recordId = "collection-7",
        )

        assertTrue(
            descriptor.copy(links = emptyList())
                .planDynamicNavigation(selectedCollection)
                .contextualFormActions
                .isEmpty(),
        )
        assertTrue(
            descriptor.planDynamicNavigation(
                selectedCollection.copy(
                    parameterValues = mapOf("collectionId" to "collection-7"),
                    actionSafeIdentity = false,
                ),
            ).contextualFormActions.isEmpty(),
        )
        assertTrue(
            descriptor.copy(
                links = descriptor.links + actionLink(
                    "collections.entries.alternate",
                    "Alternate entries",
                    "collections",
                    listEntries.id,
                ),
            ).planDynamicNavigation(selectedCollection)
                .contextualFormActions
                .isEmpty(),
        )
    }

    @Test
    fun `observed record identity binds a same-resource detail read but never a write`() {
        val detail = action("read-recipe", "recipes", ActionIntent.read, "id")
        val update = action("update-recipe", "recipes", ActionIntent.update, "id", method = HttpMethod.PUT)
        val descriptor = hierarchyDescriptor().copy(
            resources = listOf(resource("recipes")),
            layouts = emptyList(),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(detail, update),
        )
        val context = DynamicResourceRecordContext(
            resourceId = "recipes",
            recordId = "2224957",
            actionSafeIdentity = false,
        )

        assertEquals(
            mapOf("id" to "2224957"),
            descriptor.resolveDynamicRecordReadParameters(detail.id, context),
        )
        assertNull(descriptor.resolveDynamicRecordReadParameters(update.id, context))
    }

    @Test
    fun `same-resource detail remains unavailable when one selected record cannot bind every path input`() {
        val detail = action(
            "read-attachment",
            "attachments",
            ActionIntent.read,
            "cardId",
            "attachmentId",
        )
        val descriptor = hierarchyDescriptor().copy(
            resources = listOf(resource("attachments")),
            layouts = emptyList(),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(detail),
        )
        val context = DynamicResourceRecordContext(
            resourceId = "attachments",
            recordId = "attachment-9",
            fieldValues = mapOf("attachmentId" to "attachment-9"),
        )

        assertNull(descriptor.resolveDynamicRecordReadParameters(detail.id, context))
        assertEquals(
            mapOf("cardId" to "card-4", "attachmentId" to "attachment-9"),
            descriptor.resolveDynamicRecordReadParameters(
                detail.id,
                context.copy(parameterValues = mapOf("cardId" to "card-4")),
            ),
        )
    }

    @Test
    fun `path templates are parsed without platform regex assumptions`() {
        val plan = hierarchyDescriptor().planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "projects", recordId = "project-7"),
        )

        assertEquals(2, plan.contextualChildDestinations.size)
        assertTrue(plan.contextualChildDestinations.all {
            it.pathParameterValues == mapOf("projectId" to "project-7")
        })
    }

    @Test
    fun `malformed remote path templates stay inert`() {
        val descriptor = hierarchyDescriptor().let { source ->
            source.copy(
                actions = source.actions.map { action ->
                    if (action.id == "list-projects") {
                        action.copy(binding = action.binding.copy(path = "/projects/{broken"))
                    } else {
                        action
                    }
                },
            )
        }

        val plan = descriptor.planDynamicNavigation()

        assertTrue(plan.rootDestinations.isEmpty())
    }

    @Test
    fun `resource matching the app identity is the primary root destination`() {
        val source = hierarchyDescriptor()
        val descriptor = source.copy(
            app = AppIdentity("tables", "Tables", "test"),
            resources = listOf(resource("contexts"), resource("tables")),
            layouts = listOf(layout("contexts", "list-contexts"), layout("tables", "list-tables")),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(
                action("list-contexts", "contexts", ActionIntent.list),
                action("list-tables", "tables", ActionIntent.list),
            ),
        )

        assertEquals(
            listOf("tables", "contexts"),
            descriptor.planDynamicNavigation().rootDestinations.map { it.resourceId },
        )
    }

    @Test
    fun `content container outranks provisioning and helper resources`() {
        val source = hierarchyDescriptor()
        val descriptor = source.copy(
            app = AppIdentity("mail", "Mail", "test"),
            resources = listOf(
                resource("provisioning"),
                resource("account"),
                resource("mailboxes"),
                resource("outbox"),
                resource("certificates"),
            ),
            layouts = listOf(
                layout("provisioning", "list-provisioning"),
                layout("account", "list-account"),
                layout("mailboxes", "list-mailboxes"),
                layout("outbox", "list-outbox"),
                layout("certificates", "list-certificates"),
            ),
            links = listOf(
                actionLink("account.mailboxes", "Mailboxes", "account", "list-mailboxes"),
            ),
            forms = emptyList(),
            actions = listOf(
                action("list-provisioning", "provisioning", ActionIntent.list),
                action("create-provisioning", "provisioning", ActionIntent.create, method = HttpMethod.POST),
                action("update-provisioning", "provisioning", ActionIntent.update, method = HttpMethod.PUT),
                action("delete-provisioning", "provisioning", ActionIntent.delete, method = HttpMethod.DELETE),
                action("list-account", "account", ActionIntent.list),
                action("list-mailboxes", "mailboxes", ActionIntent.list, "accountId"),
                action("list-outbox", "outbox", ActionIntent.list),
                action("list-certificates", "certificates", ActionIntent.list),
            ),
        )

        val roots = descriptor.planDynamicNavigation().rootDestinations
        assertEquals("account", roots.first().resourceId)
        assertEquals(
            setOf("provisioning", "account", "outbox", "certificates"),
            roots.mapTo(hashSetOf()) { it.resourceId },
        )
    }

    @Test
    fun `primary media content outranks an editable empty organizer`() {
        val source = hierarchyDescriptor()
        val descriptor = source.copy(
            app = AppIdentity("music", "Music", "test"),
            resources = listOf(resource("playlists"), resource("tracks"), resource("genres")),
            layouts = listOf(
                layout("playlists", "list-playlists"),
                layout("tracks", "list-tracks"),
                layout("genres", "list-genres"),
            ),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(
                action("list-playlists", "playlists", ActionIntent.list),
                action("create-playlist", "playlists", ActionIntent.create, method = HttpMethod.POST),
                action("update-playlist", "playlists", ActionIntent.update, method = HttpMethod.PUT),
                action("delete-playlist", "playlists", ActionIntent.delete, method = HttpMethod.DELETE),
                action("list-tracks", "tracks", ActionIntent.list),
                action("list-genres", "genres", ActionIntent.list),
            ),
        )

        assertEquals(
            "tracks",
            descriptor.planDynamicNavigation().rootDestinations.first().resourceId,
        )
    }

    @Test
    fun `Cospend-like project self edge stays a root and never becomes its own child`() {
        val source = hierarchyDescriptor()
        val descriptor = source.copy(
            links = source.links + actionLink(
                id = "projects.projects.collection",
                label = "Project",
                resourceId = "projects",
                actionId = "list-projects",
            ),
        )

        assertEquals(
            listOf("projects"),
            descriptor.planDynamicNavigation().rootDestinations.map { it.resourceId },
        )
        assertTrue(
            descriptor.planDynamicNavigation(
                DynamicResourceRecordContext(resourceId = "projects", recordId = "project-7"),
            ).contextualChildDestinations.none { it.resourceId == "projects" },
        )
    }

    @Test
    fun `visited resource view and context state is not exposed again`() {
        val descriptor = hierarchyDescriptor()
        val destination = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "projects", recordId = "project-7"),
        ).contextualChildDestinations.single { it.resourceId == "bills" }

        val repeated = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "projects",
                recordId = "project-7",
                visitedStates = setOf(destination.navigationState()),
            ),
        )

        assertFalse(repeated.contextualChildDestinations.any { it.actionId == destination.actionId })
        assertTrue(repeated.contextualChildDestinations.any { it.resourceId == "members" })
    }

    @Test
    fun `relationship edge that closes a resource cycle is discarded`() {
        val descriptor = hierarchyDescriptor().copy(
            resources = listOf(resource("parents"), resource("children")),
            layouts = listOf(layout("parents", "list-parents"), layout("children", "list-children")),
            links = listOf(
                actionLink("01.parents.children", "Children", "parents", "list-children"),
                actionLink("02.children.parents", "Parents", "children", "list-parents"),
            ),
            forms = emptyList(),
            actions = listOf(
                action("list-parents", "parents", ActionIntent.list),
                action("list-children", "children", ActionIntent.list, "parentId"),
            ),
        )

        assertEquals(listOf("parents"), descriptor.planDynamicNavigation().rootDestinations.map { it.resourceId })
        assertEquals(
            listOf("children"),
            descriptor.planDynamicNavigation(
                DynamicResourceRecordContext(resourceId = "parents", recordId = "parent-1"),
            ).contextualChildDestinations.map { it.resourceId },
        )
        assertTrue(
            descriptor.planDynamicNavigation(
                DynamicResourceRecordContext(resourceId = "children", recordId = "child-1"),
            ).contextualChildDestinations.isEmpty(),
        )
    }

    @Test
    fun `large cyclic navigation shaped graph is deterministic and never revisits a destination`() {
        val nodeCount = 32
        val resources = (0 until nodeCount).map { index -> resource("nodes$index") }
        val rootAction = action("root-nodes0", "nodes0", ActionIntent.list)
        val childActions = (1 until nodeCount).map { index ->
            action(
                "read-nodes$index",
                "nodes$index",
                ActionIntent.list,
                "nodes${index - 1}Id",
            )
        }
        val closingAction = action(
            "read-nodes0-again",
            "nodes0",
            ActionIntent.list,
            "nodes${nodeCount - 1}Id",
        )
        val actions = listOf(rootAction) + childActions + closingAction
        val layouts = listOf(layout("nodes0", rootAction.id)) +
            childActions.mapIndexed { index, action ->
                layout("nodes${index + 1}", action.id)
            } +
            layout("nodes0", closingAction.id).copy(id = "nodes0.cyclic-list")
        val links = (1 until nodeCount).map { index ->
            actionLink(
                id = index.toString().padStart(3, '0'),
                label = "Next",
                resourceId = "nodes${index - 1}",
                actionId = "read-nodes$index",
            )
        } + actionLink(
            id = nodeCount.toString().padStart(3, '0'),
            label = "Back",
            resourceId = "nodes${nodeCount - 1}",
            actionId = closingAction.id,
        )
        val source = hierarchyDescriptor().copy(
            app = AppIdentity("cyclic", "Cyclic", "test"),
            resources = resources,
            layouts = layouts,
            links = links.reversed(),
            forms = emptyList(),
            actions = actions,
        )

        (0 until nodeCount - 1).forEach { index ->
            val context = DynamicResourceRecordContext(
                resourceId = "nodes$index",
                recordId = "record-$index",
                fieldValues = mapOf(
                    "id" to "record-$index",
                    "parentId" to "record-${index - 1}",
                    "children" to null,
                ),
            )
            val child = source.planDynamicNavigation(context).contextualChildDestinations.single()
            assertEquals("nodes${index + 1}", child.resourceId)
            assertTrue(
                source.planDynamicNavigation(
                    context.copy(visitedStates = setOf(child.navigationState())),
                ).contextualChildDestinations.isEmpty(),
            )
        }
        val closingContext = DynamicResourceRecordContext(
            resourceId = "nodes${nodeCount - 1}",
            recordId = "record-last",
        )
        val closingDestination = source.planDynamicNavigation(closingContext)
            .contextualChildDestinations.single()
        assertEquals("nodes0", closingDestination.resourceId)
        assertTrue(
            source.planDynamicNavigation(
                closingContext.copy(visitedStates = setOf(closingDestination.navigationState())),
            ).contextualChildDestinations.isEmpty(),
        )
    }

    @Test
    fun `a neutral parent with one safe child opens that child only without a dedicated surface`() {
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("generic", "Generic", "test"),
            resources = listOf(resource("containers"), resource("entries")),
            layouts = listOf(layout("containers", "list-containers"), layout("entries", "list-entries")),
            links = listOf(
                actionLink("containers.entries", "Entries", "containers", "list-entries"),
            ),
            forms = emptyList(),
            actions = listOf(
                action("list-containers", "containers", ActionIntent.list),
                action("list-entries", "entries", ActionIntent.list, "containerId"),
            ),
        )
        val context = DynamicResourceRecordContext(
            resourceId = "containers",
            recordId = "container-7",
        )

        val direct = descriptor.singleSafeContextualChild(context, hasDedicatedSurface = false)

        assertEquals("entries", direct?.resourceId)
        assertEquals(mapOf("containerId" to "container-7"), direct?.pathParameterValues)
        assertNull(descriptor.singleSafeContextualChild(context, hasDedicatedSurface = true))
    }

    @Test
    fun `semantic container routing keeps archives secondary and refuses ambiguous active mailboxes`() {
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("communications", "Communications", "test"),
            resources = listOf(resource("accounts"), resource("mailboxes"), resource("messages")),
            layouts = listOf(
                layout("accounts", "list-accounts"),
                layout("mailboxes", "list-mailboxes"),
                layout("messages", "list-messages"),
            ),
            links = listOf(
                actionLink("accounts.mailboxes", "Mailboxes", "accounts", "list-mailboxes"),
                actionLink("mailboxes.messages", "Messages", "mailboxes", "list-messages"),
            ),
            forms = emptyList(),
            actions = listOf(
                action("list-accounts", "accounts", ActionIntent.list),
                action("list-mailboxes", "mailboxes", ActionIntent.list, "accountId"),
                action("list-messages", "messages", ActionIntent.list, "mailboxId"),
            ),
        )
        val accountContext = DynamicResourceRecordContext("accounts", "7")
        val mailbox = assertNotNull(descriptor.preferredSemanticContextualChild(accountContext))
        val mailboxContext = DynamicResourceRecordContext(
            resourceId = "mailboxes",
            recordId = "9",
            parameterValues = mailbox.pathParameterValues,
        )

        assertEquals("mailboxes", mailbox.resourceId)
        assertEquals("messages", descriptor.preferredSemanticContextualChild(mailboxContext)?.resourceId)

        val withArchive = descriptor.copy(
            resources = descriptor.resources + resource("archivedMailboxes"),
            layouts = descriptor.layouts + layout("archivedMailboxes", "list-archived-mailboxes"),
            links = descriptor.links + actionLink(
                "accounts.archived-mailboxes",
                "Archived mailboxes",
                "accounts",
                "list-archived-mailboxes",
            ),
            actions = descriptor.actions + action(
                "list-archived-mailboxes",
                "archivedMailboxes",
                ActionIntent.list,
                "accountId",
            ),
        )

        assertEquals(
            "mailboxes",
            withArchive.preferredSemanticContextualChild(accountContext)?.resourceId,
        )

        val ambiguousActive = descriptor.copy(
            resources = descriptor.resources + resource("sharedMailboxes"),
            layouts = descriptor.layouts + layout("sharedMailboxes", "list-shared-mailboxes"),
            links = descriptor.links + actionLink(
                "accounts.shared-mailboxes",
                "Shared mailboxes",
                "accounts",
                "list-shared-mailboxes",
            ),
            actions = descriptor.actions + action(
                "list-shared-mailboxes",
                "sharedMailboxes",
                ActionIntent.list,
                "accountId",
            ),
        )

        assertNull(ambiguousActive.preferredSemanticContextualChild(accountContext))
    }

    @Test
    fun `semantic content containers prefer their unique declared child collection`() {
        listOf(
            "lists" to "items",
            "checklists" to "entries",
            "projects" to "tasks",
            "containers" to "entries",
        ).forEach { (parentResourceId, childResourceId) ->
            val parentAction = action("read-$parentResourceId", parentResourceId, ActionIntent.list)
            val childAction = action("read-$childResourceId", childResourceId, ActionIntent.list, "id")
            val descriptor = hierarchyDescriptor().copy(
                app = AppIdentity("workspace", "Workspace", "test"),
                resources = listOf(resource(parentResourceId), resource(childResourceId)),
                layouts = listOf(
                    layout(parentResourceId, parentAction.id),
                    layout(childResourceId, childAction.id),
                ),
                links = listOf(
                    actionLink(
                        "$parentResourceId.$childResourceId",
                        childResourceId,
                        parentResourceId,
                        childAction.id,
                    ),
                ),
                forms = emptyList(),
                actions = listOf(parentAction, childAction),
            )
            val context = DynamicResourceRecordContext(
                resourceId = parentResourceId,
                recordId = "selected-record",
            )

            val preferred = assertNotNull(descriptor.preferredSemanticContextualChild(context))

            assertEquals(childResourceId, preferred.resourceId)
            assertEquals(mapOf("id" to "selected-record"), preferred.pathParameterValues)
        }
    }

    @Test
    fun `relationship containers prefer their unique declared list collection`() {
        val parent = action("read-spaces", "spaces", ActionIntent.list)
        val lists = action("read-lists", "lists", ActionIntent.list, "id")
        val categories = action("read-categories", "categories", ActionIntent.list, "id")
        val items = action("read-items", "items", ActionIntent.list, "id")
        val notes = action("read-notes", "notes", ActionIntent.list, "id")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("organizer", "Organizer", "test"),
            resources = listOf(
                resource("spaces"),
                resource("lists"),
                resource("categories"),
                resource("items"),
                resource("notes"),
            ),
            layouts = listOf(
                layout("spaces", parent.id),
                layout("lists", lists.id),
                layout("categories", categories.id),
                layout("items", items.id),
                layout("notes", notes.id),
            ),
            links = listOf(
                actionLink("spaces.lists", "Lists", "spaces", lists.id),
                actionLink("spaces.categories", "Categories", "spaces", categories.id),
                actionLink("spaces.items", "Items", "spaces", items.id),
                actionLink("spaces.notes", "Notes", "spaces", notes.id),
            ),
            forms = emptyList(),
            actions = listOf(parent, lists, categories, items, notes),
        )
        val context = DynamicResourceRecordContext("spaces", "selected-space")

        val preferred = assertNotNull(descriptor.preferredSemanticContextualChild(context))

        assertEquals("lists", preferred.resourceId)
        assertEquals(mapOf("id" to "selected-space"), preferred.pathParameterValues)
    }

    @Test
    fun `equally meaningful declared content children remain explicit`() {
        val parent = action("read-checklists", "checklists", ActionIntent.list)
        val items = action("read-items", "items", ActionIntent.list, "id")
        val tasks = action("read-tasks", "tasks", ActionIntent.list, "id")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("workspace", "Workspace", "test"),
            resources = listOf(resource("checklists"), resource("items"), resource("tasks")),
            layouts = listOf(
                layout("checklists", parent.id),
                layout("items", items.id),
                layout("tasks", tasks.id),
            ),
            links = listOf(
                actionLink("checklists.items", "Items", "checklists", items.id),
                actionLink("checklists.tasks", "Tasks", "checklists", tasks.id),
            ),
            forms = emptyList(),
            actions = listOf(parent, items, tasks),
        )
        val context = DynamicResourceRecordContext("checklists", "selected-record")

        assertNull(descriptor.preferredSemanticContextualChild(context))
        assertEquals(
            setOf("items", "tasks"),
            descriptor.planDynamicNavigation(context).contextualChildDestinations
                .mapTo(mutableSetOf(), DynamicNavigationDestination::resourceId),
        )
    }

    @Test
    fun `declared content child with missing context is not preferred`() {
        val parent = action("read-projects", "projects", ActionIntent.list)
        val child = action("read-tasks", "tasks", ActionIntent.list, "id", "scope")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("workspace", "Workspace", "test"),
            resources = listOf(resource("projects"), resource("tasks")),
            layouts = listOf(layout("projects", parent.id), layout("tasks", child.id)),
            links = listOf(actionLink("projects.tasks", "Tasks", "projects", child.id)),
            forms = emptyList(),
            actions = listOf(parent, child),
        )
        val context = DynamicResourceRecordContext("projects", "selected-record")

        assertNull(descriptor.preferredSemanticContextualChild(context))
        assertTrue(descriptor.planDynamicNavigation(context).contextualChildDestinations.isEmpty())
        assertEquals(
            listOf("scope"),
            descriptor.explainDynamicChildNavigation(context)
                .single { it.actionId == child.id }
                .missingContextParameters,
        )
    }

    @Test
    fun `inherited ancestor identity does not turn sibling collections into record sections`() {
        val lists = action("read-lists", "lists", ActionIntent.list)
        val items = action(
            "read-items",
            "items",
            ActionIntent.list,
            "houseId",
            "listId",
        )
        val categories = action("read-categories", "categories", ActionIntent.list, "id")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("workspace", "Workspace", "test"),
            resources = listOf(resource("lists"), resource("items"), resource("categories")),
            layouts = listOf(
                layout("lists", lists.id),
                layout("items", items.id),
                layout("categories", categories.id),
            ),
            links = listOf(
                actionLink("lists.items", "Items", "lists", items.id),
                actionLink("lists.categories", "Categories", "lists", categories.id),
            ),
            forms = emptyList(),
            actions = listOf(lists, items, categories),
        )
        val context = DynamicResourceRecordContext(
            resourceId = "lists",
            recordId = "list-9",
            fieldValues = mapOf(
                "id" to "list-9",
                "listId" to "list-9",
                "houseId" to "house-7",
            ),
            parameterValues = mapOf("id" to "house-7"),
        )

        assertEquals(
            listOf("items"),
            descriptor.planDynamicNavigation(context).contextualChildDestinations
                .map(DynamicNavigationDestination::resourceId),
        )
        assertEquals(
            DynamicChildCandidateStatus.ancestorOnlyContext,
            descriptor.explainDynamicChildNavigation(context)
                .single { diagnostic -> diagnostic.actionId == categories.id }
                .status,
        )
    }

    @Test
    fun `declared content relationship cycle is never preferred`() {
        val parents = action("read-lists", "lists", ActionIntent.list, "id")
        val children = action("read-items", "items", ActionIntent.list, "id")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("workspace", "Workspace", "test"),
            resources = listOf(resource("lists"), resource("items")),
            layouts = listOf(layout("lists", parents.id), layout("items", children.id)),
            links = listOf(
                actionLink("a.items.lists", "Lists", "items", parents.id),
                actionLink("z.lists.items", "Items", "lists", children.id),
            ),
            forms = emptyList(),
            actions = listOf(parents, children),
        )
        val context = DynamicResourceRecordContext("lists", "selected-record")

        assertNull(descriptor.preferredSemanticContextualChild(context))
        assertTrue(descriptor.planDynamicNavigation(context).contextualChildDestinations.isEmpty())
        assertEquals(
            DynamicChildCandidateStatus.cycle,
            descriptor.explainDynamicChildNavigation(context)
                .single { it.actionId == children.id }
                .status,
        )
    }

    @Test
    fun `taxonomy records prefer their filtered content collection over raw detail`() {
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("library", "Library", "test"),
            resources = listOf(resource("categories"), resource("recipes")),
            layouts = listOf(
                layout("categories", "list-categories"),
                layout("recipes", "list-recipes-in-category"),
            ),
            links = listOf(
                actionLink(
                    "categories.recipes",
                    "Recipes",
                    "categories",
                    "list-recipes-in-category",
                ),
            ),
            forms = emptyList(),
            actions = listOf(
                action("list-categories", "categories", ActionIntent.list),
                action("list-recipes-in-category", "recipes", ActionIntent.list, "category"),
            ),
        )
        val context = DynamicResourceRecordContext(
            resourceId = "categories",
            recordId = "sweet",
            actionSafeIdentity = false,
        )

        val preferred = assertNotNull(descriptor.preferredSemanticContextualChild(context))

        assertEquals("recipes", preferred.resourceId)
        assertEquals(mapOf("category" to "sweet"), preferred.pathParameterValues)
    }

    @Test
    fun `taxonomy records do not auto open unrelated helper or technical collections`() {
        listOf(
            Triple("categories", "metadata", true),
            Triple("tags", "summaries", false),
        ).forEach { (parentResourceId, childResourceId, technical) ->
            val parent = action("read-$parentResourceId", parentResourceId, ActionIntent.list)
            val child = action("read-$childResourceId", childResourceId, ActionIntent.list, "id")
            val descriptor = hierarchyDescriptor().copy(
                app = AppIdentity("organizer", "Organizer", "test"),
                resources = listOf(resource(parentResourceId), resource(childResourceId)),
                layouts = listOf(
                    layout(parentResourceId, parent.id),
                    layout(childResourceId, child.id),
                ),
                links = listOf(
                    actionLink(
                        "$parentResourceId.$childResourceId",
                        childResourceId,
                        parentResourceId,
                        child.id,
                    ),
                ),
                forms = emptyList(),
                actions = listOf(parent, child),
            )
            val context = DynamicResourceRecordContext(parentResourceId, "selected-record")
            val explicitChild = descriptor.planDynamicNavigation(context)
                .contextualChildDestinations
                .single()

            assertEquals(technical, descriptor.isSecondaryTechnicalDestination(context, explicitChild))
            assertNull(descriptor.preferredSemanticContextualChild(context))
        }
    }

    @Test
    fun `archived and deleted collections remain explicit secondary sections`() {
        listOf("archive", "archivedItems", "deletedEntries", "trash").forEach { childResourceId ->
            val parent = action("read-lists", "lists", ActionIntent.list)
            val child = action("read-$childResourceId", childResourceId, ActionIntent.list, "id")
            val descriptor = hierarchyDescriptor().copy(
                app = AppIdentity("workspace", "Workspace", "test"),
                resources = listOf(resource("lists"), resource(childResourceId)),
                layouts = listOf(
                    layout("lists", parent.id),
                    layout(childResourceId, child.id),
                ),
                links = listOf(
                    actionLink("lists.$childResourceId", childResourceId, "lists", child.id),
                ),
                forms = emptyList(),
                actions = listOf(parent, child),
            )
            val context = DynamicResourceRecordContext("lists", "selected-record")
            val explicitChild = descriptor.planDynamicNavigation(context)
                .contextualChildDestinations
                .single()

            assertTrue(descriptor.isSecondaryTechnicalDestination(context, explicitChild))
            assertNull(descriptor.preferredSemanticContextualChild(context))
        }
    }

    @Test
    fun `message protocol helpers are secondary while useful content stays primary`() {
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("communications", "Communications", "test"),
            resources = listOf(
                resource("messages"),
                resource("body"),
                resource("attachments"),
                resource("dkim"),
                resource("rawSource"),
                resource("smartReply"),
                resource("thread"),
            ),
            layouts = emptyList(),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(
                action("read-body", "body", ActionIntent.read),
                action("list-attachments", "attachments", ActionIntent.list),
                action("read-dkim", "dkim", ActionIntent.read),
                action("read-raw-source", "rawSource", ActionIntent.read),
                action("read-smart-reply", "smartReply", ActionIntent.read),
                action("read-thread", "thread", ActionIntent.read),
            ),
        )
        val context = DynamicResourceRecordContext("messages", "42")
        fun destination(resourceId: String, actionId: String) = DynamicNavigationDestination(
            layoutId = "$resourceId.detail",
            label = resourceId,
            resourceId = resourceId,
            actionId = actionId,
            pathParameterValues = mapOf("messageId" to "42"),
        )

        assertFalse(descriptor.isSecondaryTechnicalDestination(context, destination("body", "read-body")))
        assertFalse(
            descriptor.isSecondaryTechnicalDestination(
                context,
                destination("attachments", "list-attachments"),
            ),
        )
        assertTrue(descriptor.isSecondaryTechnicalDestination(context, destination("dkim", "read-dkim")))
        assertTrue(
            descriptor.isSecondaryTechnicalDestination(
                context,
                destination("rawSource", "read-raw-source"),
            ),
        )
        assertTrue(
            descriptor.isSecondaryTechnicalDestination(
                context,
                destination("smartReply", "read-smart-reply"),
            ),
        )
        assertTrue(
            descriptor.isSecondaryTechnicalDestination(context, destination("thread", "read-thread")),
        )
        assertFalse(
            descriptor.isSecondaryTechnicalDestination(
                DynamicResourceRecordContext("photos", "42"),
                destination("rawSource", "read-raw-source"),
            ),
        )
    }

    @Test
    fun `optional semantic collection filter becomes a read only child facet`() {
        val albums = action("list-albums", "albums", ActionIntent.list)
        val tracks = action("list-tracks", "tracks", ActionIntent.list).copy(
            binding = DynamicHttpBinding(
                method = HttpMethod.GET,
                path = "/tracks",
                queryParameters = listOf(
                    HttpParameter("album", required = false, schema = buildJsonObject {}, source = ParameterSource.resourceField),
                    HttpParameter("limit", required = false, schema = buildJsonObject {}, source = ParameterSource.userInput),
                ),
            ),
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("music", "Music", "test"),
            resources = listOf(resource("albums"), resource("tracks")),
            layouts = listOf(layout("albums", albums.id), layout("tracks", tracks.id)),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(albums, tracks),
        )

        val child = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "albums", recordId = "784", actionSafeIdentity = false),
        ).contextualChildDestinations.single()

        assertEquals("tracks", child.resourceId)
        assertEquals(mapOf("album" to "784"), child.pathParameterValues)
    }

    @Test
    fun `nested read detail becomes a reusable record facet`() {
        val messages = action("list-messages", "messages", ActionIntent.list)
        val body = action("read-body", "body", ActionIntent.read, "id").copy(
            binding = action("read-body", "body", ActionIntent.read, "id").binding.copy(
                path = "/api/messages/{id}/body",
            ),
        )
        val unrelatedQuota = action("read-quota", "quota", ActionIntent.read, "id").copy(
            binding = action("read-quota", "quota", ActionIntent.read, "id").binding.copy(
                path = "/api/accounts/{id}/quota",
            ),
        )
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("mail", "Mail", "test"),
            resources = listOf(resource("messages"), resource("body"), resource("quota")),
            layouts = listOf(
                layout("messages", messages.id),
                DynamicLayout(
                    id = "body.detail",
                    title = "Body",
                    resourceId = "body",
                    kind = LayoutKind.detail,
                    sourceActionId = body.id,
                    confidence = Confidence.high,
                ),
                DynamicLayout(
                    id = "quota.detail",
                    title = "Quota",
                    resourceId = "quota",
                    kind = LayoutKind.detail,
                    sourceActionId = unrelatedQuota.id,
                    confidence = Confidence.high,
                ),
            ),
            links = emptyList(),
            forms = emptyList(),
            actions = listOf(messages, body, unrelatedQuota),
        )

        val facets = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = "messages",
                recordId = "29503",
                fieldValues = mapOf("id" to "29503"),
                actionSafeIdentity = false,
            ),
        ).contextualChildDestinations

        assertEquals(listOf("body"), facets.map(DynamicNavigationDestination::resourceId))
        assertEquals(mapOf("id" to "29503"), facets.single().pathParameterValues)

        assertTrue(
            descriptor.planDynamicNavigation(
                DynamicResourceRecordContext(
                    resourceId = "accounts",
                    recordId = "1",
                    fieldValues = mapOf("id" to "1"),
                ),
            ).contextualChildDestinations.none { destination -> destination.resourceId == "body" },
        )
    }

    @Test
    fun `declared links do not hide other safely bound collection children`() {
        val boards = action("list-boards", "boards", ActionIntent.list)
        val stacks = action("list-stacks", "stacks", ActionIntent.list, "boardId")
        val permissions = action("read-permissions", "permissions", ActionIntent.read, "boardId")
        val descriptor = hierarchyDescriptor().copy(
            app = AppIdentity("deck", "Deck", "test"),
            resources = listOf(resource("boards"), resource("stacks"), resource("permissions")),
            layouts = listOf(
                layout("boards", boards.id),
                layout("stacks", stacks.id),
                DynamicLayout(
                    id = "permissions.detail",
                    title = "Permissions",
                    resourceId = "permissions",
                    kind = LayoutKind.detail,
                    sourceActionId = permissions.id,
                    confidence = Confidence.high,
                ),
            ),
            links = listOf(actionLink("boards.permissions", "Permissions", "boards", permissions.id)),
            forms = emptyList(),
            actions = listOf(boards, stacks, permissions),
        )

        val children = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(resourceId = "boards", recordId = "7"),
        ).contextualChildDestinations

        assertEquals(setOf("permissions", "stacks"), children.mapTo(mutableSetOf()) { it.resourceId })
        assertTrue(children.all { it.pathParameterValues == mapOf("boardId" to "7") })
    }

    private fun hierarchyDescriptor(): DynamicAppDescriptor {
        val actions = listOf(
            action("list-projects", "projects", ActionIntent.list),
            action("list-bills", "bills", ActionIntent.list, "projectId"),
            action("list-members", "members", ActionIntent.list, "projectId"),
            action("list-views", "views", ActionIntent.list, "viewId"),
            action("create-project", "projects", ActionIntent.create, method = HttpMethod.POST),
            action("edit-project", "projects", ActionIntent.update, "projectId", method = HttpMethod.PUT),
            action("create-bill", "bills", ActionIntent.create, "projectId", method = HttpMethod.POST),
            action("edit-bill", "bills", ActionIntent.update, "projectId", "billId", method = HttpMethod.PUT),
        )
        return DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("cospend", "Cospend", "test"),
            endpointPolicy = EndpointPolicy("https://cloud.example.test"),
            resources = listOf("projects", "bills", "members", "views").map { resource(it) },
            layouts = listOf(
                layout("projects", "list-projects"),
                layout("bills", "list-bills"),
                layout("members", "list-members"),
                layout("views", "list-views"),
            ),
            links = listOf(
                actionLink("projects.bills", "Bills", "projects", "list-bills"),
                actionLink("projects.members", "Members", "projects", "list-members"),
            ),
            forms = listOf(
                form("create-project.form", "Create project", "projects", "create-project"),
                form("edit-project.form", "Edit project", "projects", "edit-project"),
                form("create-bill.form", "Create bill", "bills", "create-bill"),
                form("edit-bill.form", "Edit bill", "bills", "edit-bill"),
            ),
            actions = actions,
        )
    }

    private fun resource(id: String) = DynamicResource(
        id = id,
        label = id.replaceFirstChar(Char::uppercase),
        collection = true,
        confidence = Confidence.high,
    )

    private fun layout(resourceId: String, actionId: String) = DynamicLayout(
        id = "$resourceId.list",
        title = resourceId.replaceFirstChar(Char::uppercase),
        resourceId = resourceId,
        kind = LayoutKind.list,
        sourceActionId = actionId,
        confidence = Confidence.high,
    )

    private fun actionLink(id: String, label: String, resourceId: String, actionId: String) = DynamicLink(
        id = id,
        label = label,
        resourceId = resourceId,
        sourceFieldId = "id",
        target = DynamicLinkTarget.Action(actionId),
        confidence = Confidence.high,
    )

    private fun form(id: String, title: String, resourceId: String, actionId: String) = DynamicForm(
        id = id,
        title = title,
        resourceId = resourceId,
        actionId = actionId,
        confidence = Confidence.high,
    )

    private fun action(
        id: String,
        resourceId: String,
        intent: ActionIntent,
        vararg pathParameters: String,
        method: HttpMethod = HttpMethod.GET,
        body: HttpBody? = null,
    ) = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = intent,
        risk = if (method == HttpMethod.GET) ActionRisk.readOnly else ActionRisk.mutating,
        requiresConfirmation = method != HttpMethod.GET,
        binding = DynamicHttpBinding(
            method = method,
            path = "/$resourceId" + pathParameters.joinToString(separator = "", prefix = "") { "/{$it}" },
            pathParameters = pathParameters.map { parameter ->
                HttpParameter(parameter, required = true, schema = buildJsonObject {}, source = ParameterSource.resourceField)
            },
            body = body,
        ),
        confidence = Confidence.high,
    )
}
