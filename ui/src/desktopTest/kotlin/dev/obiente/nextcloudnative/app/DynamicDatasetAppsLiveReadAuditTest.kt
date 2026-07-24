package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.expandNestedBoardDataset
import dev.obiente.nextcloudnative.nativeui.runtime.hydrateNativeDataset
import dev.obiente.nextcloudnative.nativeui.runtime.nativeBoardLanes
import dev.obiente.nextcloudnative.nativeui.runtime.nativeBoardCardActionPlan
import dev.obiente.nextcloudnative.nativeui.runtime.nativeBoardLaneCreatePlan
import dev.obiente.nextcloudnative.nativeui.runtime.NativeBoardDirectActionKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCellEditPlan
import dev.obiente.nextcloudnative.nativeui.runtime.nativeDatasetInsights
import dev.obiente.nextcloudnative.nativeui.runtime.nativeTableProjection
import dev.obiente.nextcloudnative.nativeui.runtime.withEphemeralDisplayFields
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Saved-session, response-content-redacted audit for reusable grid, board and finance semantics.
 * The delegating transport rejects every request that is not an empty-body GET before execution.
 */
class DynamicDatasetAppsLiveReadAuditTest {
    @Test
    fun `live Cospend bill envelope becomes individual native records`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_COSPEND_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        val services = GetOnlyAuditServices(delegate, mutableListOf())
        val descriptor = discover(services, session, services.loadServerInfo(session), "cospend")
        val projectRoot = assertNotNull(
            descriptor.planDynamicNavigation().rootDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("project", "projects") }
            },
        )
        val project = assertNotNull(
            loadDynamicRecords(services, session, descriptor, projectRoot.actionId).firstOrNull(),
        )
        val billDestination = assertNotNull(
            descriptor.planDynamicNavigation(project.context(projectRoot.resourceId))
                .contextualChildDestinations
                .firstOrNull { destination ->
                    destination.resourceId.semanticWords().any { word -> word in setOf("bill", "bills") }
                },
        )
        val bills = loadDestination(services, session, descriptor, billDestination)
        val action = assertNotNull(descriptor.actions.firstOrNull { it.id == billDestination.actionId })
        val fallbackMetadata = action.fallbackActionIds.mapNotNull { id ->
            descriptor.actions.firstOrNull { it.id == id }
        }.map { fallback -> "${fallback.id}:${fallback.intent}:${fallback.resourceId}" }
        val advertisedCount = bills.singleOrNull()
            ?.displayValues
            ?.entries
            ?.firstOrNull { (key, _) ->
                key.lowercase().filter(Char::isLetterOrDigit) == "nbbills"
            }
            ?.value
            ?.toIntOrNull()
        assertTrue(
            advertisedCount == null || advertisedCount <= 1 || bills.size > 1,
            "Envelope was not expanded: action=${action.id}:${action.intent}:${action.resourceId} " +
                "fallbacks=$fallbackMetadata count=$advertisedCount " +
                "keys=${bills.singleOrNull()?.displayValues?.keys?.sorted()}",
        )
    }

    @Test
    fun `live Deck cards expose exact signed edit and move plans without mutation`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_DECK_ACTION_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        val requests = mutableListOf<NextcloudApiRequest>()
        val services = GetOnlyAuditServices(delegate, requests)

        auditDeck(services, session, services.loadServerInfo(session))

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { request ->
            request.method == NextcloudApiMethod.GET && request.body == null
        })
    }

    @Test
    fun `live table board and finance datasets retain native semantics`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_DATASET_AUDIT") != "1") return@runBlocking
        val delegate = DesktopNextcloudServices()
        val session = assertNotNull(delegate.loadSession())
        val requests = mutableListOf<NextcloudApiRequest>()
        val services = GetOnlyAuditServices(delegate, requests)
        val server = services.loadServerInfo(session)

        auditTables(services, session, server)
        auditDeck(services, session, server)
        auditFinance(services, session, server, "cospend")
        auditFinance(services, session, server, "budget")

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { request ->
            request.method == NextcloudApiMethod.GET && request.body == null
        })
        println(
            "dataset-app-audit outcome=success methods=get-only " +
                "tables=joined-grid deck=lanes-cards finance=totals-breakdowns content=redacted",
        )
    }

    private suspend fun auditTables(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ) {
        val descriptor = discover(services, session, server, "tables")
        val schema = descriptor.toNativeAppSchema()
        val tableRoot = assertNotNull(
            descriptor.planDynamicNavigation().rootDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("table", "tables") }
            },
        )
        val tables = loadDynamicRecords(services, session, descriptor, tableRoot.actionId)
        val table = assertNotNull(tables.firstOrNull())
        val context = table.context(tableRoot.resourceId)
        val plan = descriptor.planDynamicNavigation(context)
        val compositeView = assertNotNull(schema.views.firstOrNull { view ->
            view.compositeDataGrid?.parentResourceId == tableRoot.resourceId
        })
        val composite = assertNotNull(compositeView.compositeDataGrid)
        val columnDestination = assertNotNull(
            plan.contextualChildDestinations.firstOrNull { it.actionId == composite.columnSourceActionId },
        )
        val rowDestination = assertNotNull(
            plan.contextualChildDestinations.firstOrNull { it.actionId == composite.rowSourceActionId },
        )
        val columns = loadDestination(services, session, descriptor, columnDestination)
        val rows = loadDestination(services, session, descriptor, rowDestination)
        assertTrue(columns.isNotEmpty())
        assertTrue(rows.isNotEmpty())
        val columnResource = assertNotNull(schema.resources.firstOrNull { it.id == composite.columnResourceId })
            .withEphemeralDisplayFields(columns)
        val rowResource = assertNotNull(schema.resources.firstOrNull { it.id == composite.rowResourceId })
            .withEphemeralDisplayFields(rows)
        val projection = nativeTableProjection(rowResource, rows, columnResource, columns, composite)

        assertTrue(projection.composite)
        assertTrue(projection.projectedFieldIds.isNotEmpty())
        assertTrue(projection.records.size == rows.size)
        assertTrue(projection.projectedFieldIds.none { fieldId ->
            fieldId.substringAfterLast('.').normalizedKey() == "id"
        })
        val editableCell = rows.asSequence().flatMap { row ->
            projection.resource.fields.asSequence()
                .filter { field -> field.id in projection.projectedFieldIds }
                .mapNotNull { field -> nativeCellEditPlan(schema, rowResource, projection, row, field) }
        }.firstOrNull()
        assertNotNull(
            editableCell,
            "The signed Tables package must yield at least one schema-gated, lossless single-cell edit plan " +
                "over its declared row write. " +
                "rows=${rows.take(2).map { row ->
                    mapOf(
                        "id" to row.id,
                        "safeIdentity" to row.actionSafeIdentity,
                        "valueKeys" to row.values.keys,
                        "valueShapes" to row.values.mapValues { (_, value) ->
                            value?.trim()?.let { observed ->
                                when {
                                    observed.startsWith("{") -> "object(${observed.length})"
                                    observed.startsWith("[") -> "array(${observed.length})"
                                    else -> "scalar(${observed.length})"
                                }
                            } ?: "null"
                        },
                    )
                }} " +
                "projected=${projection.cellsByRecord.entries.take(2).associate { (recordId, cells) ->
                    recordId to cells.mapValues { (_, cell) ->
                        mapOf(
                            "source" to cell.sourceFieldId,
                            "key" to cell.cellKey,
                            "contextKeys" to cell.contextValues.keys,
                            "shape" to cell.valueShape,
                            "declaredKind" to cell.declaredKind,
                        )
                    }
                }} " +
                "rowActions=${schema.actions.filter { it.resourceId == rowResource.id }.map { action ->
                    action.id to action.binding
                }}",
        )
    }

    private suspend fun auditDeck(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
    ) {
        val descriptor = discover(services, session, server, "deck")
        val schema = descriptor.toNativeAppSchema()
        val boardRoot = assertNotNull(
            descriptor.planDynamicNavigation().rootDestinations.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("board", "boards") }
            },
        )
        val board = assertNotNull(
            loadDynamicRecords(services, session, descriptor, boardRoot.actionId).firstOrNull(),
        )
        val stackDestination = assertNotNull(
            descriptor.planDynamicNavigation(board.context(boardRoot.resourceId))
                .contextualChildDestinations.firstOrNull { destination ->
                    destination.resourceId.semanticWords().any { it in setOf("stack", "stacks", "lane", "lanes") }
                },
        )
        val stacks = loadDestination(services, session, descriptor, stackDestination)
        assertTrue(stacks.isNotEmpty())
        val stackResource = assertNotNull(schema.resources.firstOrNull { it.id == stackDestination.resourceId })
            .withEphemeralDisplayFields(stacks)
        val expanded = assertNotNull(expandNestedBoardDataset(schema, stackResource, stacks))
        val lanes = expanded.boardLanes ?: nativeBoardLanes(expanded.resource, expanded.records)

        assertTrue(lanes.isNotEmpty())
        assertTrue(lanes.flatMap { lane -> lane.records }.isNotEmpty())
        assertTrue(lanes.all { lane -> lane.title.isNotBlank() })
        val card = assertNotNull(lanes.asSequence().flatMap { lane -> lane.records.asSequence() }.firstOrNull())
        val actionPlan = nativeBoardCardActionPlan(schema, expanded.resource, card, lanes)
        val edit = assertNotNull(actionPlan.edit)
        val move = assertNotNull(actionPlan.move)
        val editRequest = edit.request(edit.initialValues)
        val moveTarget = assertNotNull(move.targets.firstOrNull())
        val moveRequest = move.request(moveTarget.key)
        val create = lanes.firstNotNullOfOrNull { lane ->
            nativeBoardLaneCreatePlan(schema, expanded.resource, lane)
        } ?: error(
            "No exact create plan. resource=${expanded.resource.id} laneContext=" +
                lanes.first().contextValues.keys.sorted().joinToString(",") +
                " candidates=" + schema.actions.filter { action ->
                    action.intent == ActionIntent.create
                }.joinToString("|") { action ->
                    "${action.resourceId}:${action.binding.method}:${action.binding.path}:" +
                        "path=${action.binding.requiredPathParameterNames.sorted()}:" +
                        "body=${action.binding.bodyFieldNames.sorted()}:" +
                        "required=${action.binding.requiredBodyFieldNames.sorted()}:" +
                        "type=${action.binding.bodyContentType}"
                },
        )
        val createRequest = create.request("Contract audit card")
        val directKinds = actionPlan.directActions.mapTo(mutableSetOf()) { it.kind }
        assertTrue(edit.action.binding.path.contains("/cards/{cardId}/"))
        assertTrue(move.action.binding.path.contains("/cards/{cardId}/"))
        assertTrue(edit.action.binding.bodyFieldNames.all(editRequest.values::containsKey))
        assertTrue(move.action.binding.requiredBodyFieldNames.all(moveRequest.values::containsKey))
        assertTrue(moveRequest.values[move.laneBodyFieldName] == moveTarget.key)
        assertTrue(create.action.binding.requiredPathParameterNames.all(createRequest.values::containsKey))
        assertTrue(create.action.binding.requiredBodyFieldNames.all(createRequest.values::containsKey))
        assertTrue(
            NativeBoardDirectActionKind.Delete in directKinds,
            "Signed Deck card deletion should be contract-gated and available for explicit confirmation.",
        )
        assertTrue(
            directKinds.any {
                it == NativeBoardDirectActionKind.Archive ||
                    it == NativeBoardDirectActionKind.Unarchive
            },
            "Signed Deck card archive state should expose exactly one matching transition. actions=" +
                schema.actions.filter { action ->
                    action.id.contains("archive", ignoreCase = true) ||
                        action.label.contains("archive", ignoreCase = true) ||
                        action.binding.path.contains("archive", ignoreCase = true)
                }.joinToString("|") { action ->
                    "${action.id}:${action.resourceId}:${action.intent}:${action.risk}:" +
                        "${action.binding.method}:${action.binding.path}"
                } + " fields=${card.values.keys.sorted()} display=${card.displayValues.keys.sorted()} " +
                "laneContext=${lanes.firstOrNull { lane -> lane.records.any { it.id == card.id } }?.contextValues}",
        )
        println(
            "deck-action-audit identity=signed-path-safe " +
                "edit=${edit.action.binding.method}:${edit.action.binding.path}:" +
                edit.action.binding.bodyFieldNames.sorted().joinToString(",") +
                " move=${move.action.binding.method}:${move.action.binding.path}:" +
                move.action.binding.bodyFieldNames.sorted().joinToString(",") +
                " create=${create.action.binding.method}:${create.action.binding.path}:" +
                create.action.binding.bodyFieldNames.sorted().joinToString(",") +
                " direct=${directKinds.sortedBy { it.name }.joinToString(",")} " +
                " content=redacted",
        )
    }

    private suspend fun auditFinance(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
        appId: String,
    ) {
        val descriptor = discover(services, session, server, appId)
        val schema = descriptor.toNativeAppSchema()
        val roots = descriptor.planDynamicNavigation().rootDestinations
        val rootResults = roots.mapNotNull { destination ->
            val records = runCatching {
                loadDynamicRecords(services, session, descriptor, destination.actionId)
            }.getOrNull().orEmpty()
            if (records.isEmpty()) return@mapNotNull null
            val resource = schema.resources.firstOrNull { it.id == destination.resourceId }
                ?.withEphemeralDisplayFields(records)
                ?: return@mapNotNull null
            val hydrated = hydrateNativeDataset(schema, resource, records, NativeDatasetContext())
            nativeDatasetInsights(hydrated.resource, hydrated.records)?.let { destination to it }
        }
        assertTrue(rootResults.isNotEmpty(), "$appId did not expose a meaningful numeric dataset.")

        if (appId == "cospend") {
            val projectRoot = assertNotNull(roots.firstOrNull { destination ->
                destination.resourceId.semanticWords().any { it in setOf("project", "projects") }
            })
            val project = assertNotNull(
                loadDynamicRecords(services, session, descriptor, projectRoot.actionId).firstOrNull(),
            )
            val projectContext = project.context(projectRoot.resourceId)
            val billDestination = assertNotNull(
                descriptor.planDynamicNavigation(projectContext).contextualChildDestinations.firstOrNull { destination ->
                    destination.resourceId.semanticWords().any {
                        it in setOf("bill", "bills", "expense", "expenses", "transaction", "transactions")
                    }
                },
            )
            val bills = loadDestination(services, session, descriptor, billDestination)
            if (bills.isNotEmpty()) {
                val advertisedCount = bills.singleOrNull()
                    ?.displayValues
                    ?.entries
                    ?.firstOrNull { (key, _) ->
                        key.lowercase().filter(Char::isLetterOrDigit) in setOf("nbbills", "total", "totalcount")
                    }
                    ?.value
                    ?.toIntOrNull()
                assertTrue(
                    advertisedCount == null || advertisedCount <= 1 || bills.size > 1,
                        "Collection envelope remained one record: resource=${billDestination.resourceId} " +
                        "action=${billDestination.actionId} count=$advertisedCount " +
                        "keys=${bills.firstOrNull()?.displayValues?.keys?.sorted()}",
                )
                val billResource = assertNotNull(schema.resources.firstOrNull { it.id == billDestination.resourceId })
                    .withEphemeralDisplayFields(bills)
                val hydrated = hydrateNativeDataset(
                    schema,
                    billResource,
                    bills,
                    NativeDatasetContext(
                        parentResourceId = projectRoot.resourceId,
                        parentRecord = project,
                    ),
                )
                assertNotNull(
                    nativeDatasetInsights(hydrated.resource, hydrated.records),
                    "fields=${hydrated.resource.fields.map { it.id to it.kind }} " +
                        "keys=${hydrated.records.first().displayValues.keys.sorted()}",
                )
            }
        }
    }

    private suspend fun loadDestination(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        descriptor: DynamicAppDescriptor,
        destination: DynamicNavigationDestination,
    ): List<NativeRecord> = loadDynamicRecords(
        services,
        session,
        descriptor,
        destination.actionId,
        destination.pathParameterValues,
        destination.pathParameterValues,
    )

    private suspend fun discover(
        services: NextcloudPlatformServices,
        session: NextcloudSession,
        server: NextcloudServerInfo,
        appId: String,
    ): DynamicAppDescriptor {
        val app = assertNotNull(server.apps.firstOrNull { app -> app.id == appId })
        val discovery = discoverDynamicAppDescriptor(services, session, app, server.version)
        assertTrue(discovery.acquisition != DynamicDescriptorAcquisition.MetadataFallback)
        return discovery.descriptor
    }

    private fun NativeRecord.context(resourceId: String): DynamicResourceRecordContext =
        DynamicResourceRecordContext(
            resourceId = resourceId,
            recordId = id,
            fieldValues = values,
            actionSafeIdentity = actionSafeIdentity,
        )

    private fun String.semanticWords(): Set<String> = lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()

    private fun String.normalizedKey(): String = lowercase().filter(Char::isLetterOrDigit)

    private class GetOnlyAuditServices(
        private val delegate: NextcloudPlatformServices,
        private val observed: MutableList<NextcloudApiRequest>,
    ) : NextcloudPlatformServices by delegate {
        override suspend fun executeNextcloudApi(
            session: NextcloudSession,
            request: NextcloudApiRequest,
        ): NextcloudApiResponse {
            assertTrue(request.method == NextcloudApiMethod.GET)
            assertTrue(request.body == null)
            observed += request
            return delegate.executeNextcloudApi(session, request)
        }
    }
}
