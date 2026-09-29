package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestinationSection
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredEntry
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredScalarKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBudgetSemanticsTest {
    @Test
    fun mapsBudgetRoutesToRecognizableProductLanguage() {
        assertEquals("Budget", nativeBudgetDestinationSemantics("budget", "budget")?.label)
        assertEquals("Recurring budgets", nativeBudgetDestinationSemantics("budget", "recurring-budgets")?.label)
        assertEquals("Income", nativeBudgetDestinationSemantics("budget", "recurring_income")?.label)
        assertEquals("Shared expenses", nativeBudgetDestinationSemantics("budget", "settlements")?.label)
        assertEquals("Forecast", nativeBudgetDestinationSemantics("budget", "trends")?.label)
    }

    @Test
    fun keepsCoreWorkflowsAheadOfTechnicalCollections() {
        val accounts = requireNotNull(nativeBudgetDestinationSemantics("budget", "accounts"))
        val status = requireNotNull(nativeBudgetDestinationSemantics("budget", "status"))
        assertEquals(NextcloudCollectionDestinationSection.Primary, accounts.section)
        assertEquals(NextcloudCollectionDestinationSection.Manage, status.section)
        assertTrue(accounts.order < status.order)
    }

    @Test
    fun doesNotApplyBudgetLanguageToAnotherApp() {
        assertNull(nativeBudgetDestinationSemantics("tables", "accounts"))
    }

    @Test
    fun prefersRichBudgetReportWhilePreservingRecurringFallback() {
        assertEquals(
            setOf("accounts", "budget"),
            nativeBudgetVisibleRootResourceIds("budget", listOf("accounts", "recurring-budgets", "budget")),
        )
        assertEquals(
            setOf("accounts", "recurring-budgets"),
            nativeBudgetVisibleRootResourceIds("budget", listOf("accounts", "recurring-budgets")),
        )
        assertEquals(
            setOf("budget", "recurring-budgets"),
            nativeBudgetVisibleRootResourceIds("tables", listOf("budget", "recurring-budgets")),
        )
    }

    @Test
    fun editableRecurringBudgetsRemainReachableBesideTheReport() {
        assertEquals(
            setOf("budget", "recurring-budgets"),
            nativeBudgetVisibleRootResourceIds(
                "budget",
                listOf("budget", "recurring-budgets"),
                setOf("recurring-budgets"),
            ),
        )
    }

    @Test
    fun coversEveryVerifiedBudgetRootFromTheSignedContract() {
        val resources = setOf(
            "accounts", "alerts", "assets", "balances", "banking-institutions", "bills",
            "budget", "budget-snapshots", "categories", "contacts", "debt-scenarios", "debts",
            "duplicates", "pensions", "progress", "recurring-budgets", "recurring-income",
            "report-mutes", "saved", "savings-goals", "scan-matches", "settlements",
            "snapshots", "status", "suggestions", "tag-sets", "transaction-counts",
            "transaction-ids", "transactions", "trends", "unrecorded-payments", "years",
        )
        assertEquals(
            emptySet(),
            resources.filterTo(mutableSetOf()) { resource ->
                nativeBudgetDestinationSemantics("budget", resource) == null
            },
        )
    }

    @Test
    fun addsAContractBackedBudgetDashboardWithoutInventingAnEndpoint() {
        val accounts = ViewSpec(
            id = "accounts.list",
            title = "Accounts",
            resourceId = "accounts",
            component = NativeComponent.collectionList,
            sourceActionId = "accounts.list",
            confidence = Confidence.verified,
        )
        val createTransaction = ActionSpec(
            id = "transactions.create",
            label = "Create",
            resourceId = "transactions",
            binding = dev.obiente.nextcloudnative.nativeui.model.ApiBinding(
                method = HttpMethod.POST,
                path = "/apps/budget/api/transactions",
                operationId = "createTransaction",
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val budgetReport = ActionSpec(
            id = "reports.budget",
            label = "Budget report",
            resourceId = "reports",
            binding = dev.obiente.nextcloudnative.nativeui.model.ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/budget/api/reports/budget",
                operationId = "budgetReport",
            ),
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("budget", "Budget", "2.39.1"),
            confidence = Confidence.verified,
            views = listOf(accounts),
            actions = listOf(accountsListAction(), createTransaction, budgetReport),
        )
        val adapted = schema.withNativeBudgetDashboard()
        val dashboard = adapted.views.first()
        assertEquals(NATIVE_BUDGET_DASHBOARD_VIEW_ID, dashboard.id)
        assertEquals(NativeComponent.dashboard, dashboard.component)
        assertEquals(accounts.sourceActionId, dashboard.sourceActionId)
        assertEquals(accounts.resourceId, dashboard.resourceId)
        assertEquals("Add transaction", adapted.actions.first { it.id == createTransaction.id }.label)
        val budgetPlan = adapted.views.first { it.id == NATIVE_BUDGET_PLAN_VIEW_ID }
        assertEquals("budget", budgetPlan.resourceId)
        assertEquals(budgetReport.id, budgetPlan.sourceActionId)
        assertEquals("Budget", adapted.resources.first { it.id == "budget" }.name)
    }

    @Test
    fun excludesFallbackOnlyDescriptorBudgetReportWhenLegacySchemaOmitsIt() {
        val accounts = ViewSpec(
            id = "accounts.list",
            title = "Accounts",
            resourceId = "accounts",
            component = NativeComponent.collectionList,
            sourceActionId = "accounts.list",
            confidence = Confidence.verified,
        )
        val descriptorReport = DynamicAction(
            id = "route-report-budget",
            label = "Budget report",
            resourceId = "reports",
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(HttpMethod.GET, "/apps/budget/api/reports/budget"),
            fallbackOnly = true,
            confidence = Confidence.high,
        )
        val schema = NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("budget", "Budget", "2.39.1"),
            confidence = Confidence.verified,
            views = listOf(accounts),
            actions = listOf(accountsListAction()),
        )

        val adapted = schema.withNativeBudgetDashboard(listOf(descriptorReport))

        assertTrue(adapted.views.none { it.id == NATIVE_BUDGET_PLAN_VIEW_ID })
        assertTrue(adapted.actions.none { it.id == descriptorReport.id })
    }

    @Test
    fun dashboardLoadsOnlyExistingVerifiedRootDestinationsInProductOrder() {
        val advertised = listOf("trends", "status", "accounts", "bills").map { resource ->
            DynamicNavigationDestination(
                layoutId = "$resource.list",
                label = resource,
                resourceId = resource,
                actionId = "$resource.list",
            )
        }
        assertEquals(
            listOf("accounts", "bills", "trends"),
            nativeBudgetDashboardReadDestinations("budget", advertised).map { it.resourceId },
        )
        assertEquals(emptyList(), nativeBudgetDashboardReadDestinations("tables", advertised))
    }

    @Test
    fun dashboardNumbersAreCompactWithoutLosingCents() {
        assertEquals("42", formatNativeBudgetNumber(42.0))
        assertEquals("42.50", formatNativeBudgetNumber(42.5))
        assertEquals("42.57", formatNativeBudgetNumber(42.567))
        assertEquals("EUR 1,234.50", formatNativeBudgetMoney(1234.5, "EUR"))
    }

    @Test
    fun financeSummaryModelUsesDedicatedSummaryActions() {
        val accountSummary = budgetRead("account-summary", "accounts", "/apps/budget/api/accounts/summary")
        val reports = budgetRead("report-summary", "reports", "/apps/budget/api/reports/summary")
        val accounts = budgetRead("accounts-list", "accounts", "/apps/budget/api/accounts").copy(intent = ActionIntent.list)
        val reads = nativeBudgetDashboardReads("budget", listOf(accounts, reports, accountSummary))
        val model = buildNativeBudgetDashboardModel(
            reads,
            mapOf(
                accountSummary.id to listOf(
                    NativeRecord("summary", mapOf("netWorth" to "15322.56")),
                ),
                reports.id to listOf(
                    NativeRecord(
                        "summary",
                        mapOf("baseCurrency" to "GBP"),
                        structuredValues = mapOf(
                            "totals" to NativeStructuredValue.ObjectValue(
                                entries = listOf(
                                    NativeStructuredEntry(
                                        "totalIncome",
                                        "Total income",
                                        NativeStructuredValue.Scalar("4500", NativeStructuredScalarKind.number),
                                    ),
                                    NativeStructuredEntry(
                                        "totalExpenses",
                                        "Total expenses",
                                        NativeStructuredValue.Scalar("2295.27", NativeStructuredScalarKind.number),
                                    ),
                                ),
                            ),
                            "trends" to NativeStructuredValue.ObjectValue(
                                entries = listOf(
                                    NativeStructuredEntry(
                                        "labels", "Labels", NativeStructuredValue.ListValue(
                                            listOf("Jan", "Feb").map {
                                                NativeStructuredValue.Scalar(it, NativeStructuredScalarKind.string)
                                            },
                                        ),
                                    ),
                                    NativeStructuredEntry(
                                        "income", "Income", NativeStructuredValue.ListValue(
                                            listOf("4200", "4500").map {
                                                NativeStructuredValue.Scalar(it, NativeStructuredScalarKind.number)
                                            },
                                        ),
                                    ),
                                    NativeStructuredEntry(
                                        "expenses", "Expenses", NativeStructuredValue.ListValue(
                                            listOf("2000", "2295.27").map {
                                                NativeStructuredValue.Scalar(it, NativeStructuredScalarKind.number)
                                            },
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                accounts.id to listOf(
                    NativeRecord("1", mapOf("name" to "Main account", "balance" to "3467.82", "currency" to "GBP")),
                ),
            ),
        )
        assertEquals(15322.56, model.netWorth?.value)
        assertEquals(4500.0, model.income?.value)
        assertEquals(2295.27, model.expenses?.value)
        assertEquals(2204.73, model.savings?.value)
        assertEquals("GBP", model.currency)
        assertEquals("Main account", model.accounts.single().name)
        assertEquals(listOf("Jan", "Feb"), model.trends.map(NativeBudgetTrendPoint::label))
        assertEquals(4500.0, model.trends.last().income)
    }

    @Test
    fun dashboardReadsExcludeFallbackOnlyRoutes() {
        val fallback = budgetRead("budget-fallback", "budget", "/apps/budget/api/reports/budget")
            .copy(fallbackOnly = true)

        assertTrue(nativeBudgetDashboardReads("budget", listOf(fallback)).isEmpty())
    }

    @Test
    fun dashboardReadsSupplyRequiredQueryParametersWhenTheDashboardDeclaresValues() {
        val transactions = budgetRead("transactions", "transactions", "/apps/budget/api/transactions")
            .copy(
                binding = DynamicHttpBinding(
                    method = HttpMethod.GET,
                    path = "/apps/budget/api/transactions",
                    queryParameters = listOf(
                        HttpParameter("limit", true, JsonPrimitive("integer"), ParameterSource.userInput),
                    ),
                ),
            )

        val read = nativeBudgetDashboardReads("budget", listOf(transactions)).single()
        assertEquals(NativeBudgetDashboardDataKind.RecentTransactions, read.kind)
        assertEquals(mapOf("limit" to "5"), read.values)
    }

    @Test
    fun dashboardSkipsAccountDetailAndRequiresAnUnboundCollectionRead() {
        val list = accountsListAction()
        val listView = ViewSpec("accounts.list", "Accounts", "accounts", NativeComponent.collectionList,
            list.id, Confidence.verified)
        val detail = list.copy(id = "accounts.detail", intent = ActionIntent.read,
            binding = list.binding.copy(path = "/apps/budget/api/accounts/{id}",
                pathParameterNames = listOf("id"), requiredPathParameterNames = listOf("id")))
        val detailView = listView.copy(id = "accounts.detail", component = NativeComponent.detail,
            sourceActionId = detail.id)
        val schema = NativeAppSchema("test", AppIdentity("budget", "Budget", "2.54.0"), Confidence.high,
            actions = listOf(detail, list), views = listOf(detailView, listView))
        assertEquals(list.id, schema.withNativeBudgetDashboard().views.first().sourceActionId)
        listOf(
            list.copy(binding = list.binding.copy(requiredPathParameterNames = listOf("id"))),
            list.copy(binding = list.binding.copy(requiredQueryParameterNames = listOf("accountId"))),
            list.copy(binding = list.binding.copy(method = HttpMethod.POST)),
            list.copy(intent = ActionIntent.read),
            list.copy(risk = ActionRisk.mutating),
            list.copy(confidence = Confidence.low),
        ).forEach { unavailable ->
            val adapted = schema.copy(actions = listOf(detail, unavailable)).withNativeBudgetDashboard()
            assertTrue(adapted.views.none { it.id == NATIVE_BUDGET_DASHBOARD_VIEW_ID })
        }
        assertTrue(schema.copy(actions = listOf(detail)).withNativeBudgetDashboard().views.none {
            it.id == NATIVE_BUDGET_DASHBOARD_VIEW_ID
        })
    }

    @Test
    fun signedBudget254DescriptorWithoutAccountsCollectionViewStillOpensDashboard() {
        // Exact acquired shape: the OCS list action survives, but only a detail layout is inferred.
        val proof = listOf(Provenance(ProvenanceKind.verifiedAppPackage,
            "https://fixture.invalid/openapi.json", "Budget 2.54.0 contract fragment"))
        val list = DynamicAction("listaccounts", "List accounts", "accounts", ActionIntent.list,
            ActionRisk.readOnly, false, DynamicHttpBinding(HttpMethod.GET,
                "/ocs/v2.php/apps/budget/api/v1/accounts"), responseFieldIds = listOf("ocs"),
            confidence = Confidence.high, provenance = proof)
        val detail = list.copy(id = "route-account-show", intent = ActionIntent.read,
            binding = DynamicHttpBinding(HttpMethod.GET, "/apps/budget/api/accounts/{id}",
                pathParameters = listOf(HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField))))
        val descriptor = DynamicAppDescriptor(DYNAMIC_APP_DESCRIPTOR_VERSION,
            AppIdentity("budget", "Budget", "2.54.0"), EndpointPolicy("https://fixture.invalid:8443",
                listOf("/apps/budget", "/ocs/v2.php/apps/budget")),
            resources = listOf(DynamicResource("accounts", "Accounts", true, listOf(
                DynamicField("ocs", "Ocs", FieldKind.objectValue, true, false, false, false,
                    confidence = Confidence.high, provenance = proof)),
                confidence = Confidence.high, provenance = proof)),
            actions = listOf(detail, list), layouts = listOf(DynamicLayout("accounts.detail", "Accounts",
                "accounts", LayoutKind.detail, sourceActionId = detail.id, confidence = Confidence.high, provenance = proof)))
        val mapped = descriptor.toNativeAppSchema()
        assertEquals(listOf(NativeComponent.detail), mapped.views.map { it.component })
        val adapted = mapped.withNativeBudgetDashboard(descriptor.actions)
        val dashboard = adapted.views.first()
        assertEquals(NATIVE_BUDGET_DASHBOARD_VIEW_ID, dashboard.id)
        assertEquals("accounts", dashboard.resourceId)
        assertEquals("listaccounts", dashboard.sourceActionId)
        val action = adapted.actions.single { it.id == dashboard.sourceActionId }
        assertTrue(action.binding.requiredPathParameterNames.isEmpty())
        assertTrue(action.binding.requiredQueryParameterNames.isEmpty())
        assertEquals(mapped.views, adapted.views.drop(1))
        assertEquals(mapped.actions, adapted.actions)
    }
    @Test
    fun verifiedOcsAccountsPopulateDashboardWithoutInventingNetWorthFromPartialBalances() {
        val primary = budgetRead("listaccounts", "accounts", "/ocs/v2.php/apps/budget/api/v1/accounts").copy(
            intent = ActionIntent.list, responseFieldIds = listOf("ocs"),
            binding = DynamicHttpBinding(HttpMethod.GET, "/ocs/v2.php/apps/budget/api/v1/accounts",
                ocs = OcsMetadata(apiRequestHeader = true, responseDataPointer = "/ocs/data", responseMetaPointer = "/ocs/meta")))
        val fallback = primary.copy(id = "route-account-index", fallbackOnly = true,
            binding = DynamicHttpBinding(HttpMethod.GET, "/apps/budget/api/accounts"))
        val reads = nativeBudgetDashboardReads("budget", listOf(fallback, primary))
        assertEquals(primary.id, reads.single().action.id)
        val records = parseDynamicRecords(primary, NextcloudApiResponse(200,
            """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[{"id":1,"name":"Synthetic account","balance":"42.50","currency":"EUR"}]}}""".encodeToByteArray(),
            "application/json", null), primary.responseFieldIds.toSet())
        val model = buildNativeBudgetDashboardModel(reads, mapOf(primary.id to records))
        assertEquals("Synthetic account", model.accounts.single().name)
        assertEquals(42.5, model.accounts.single().balance)
        assertEquals("EUR", model.accounts.single().currency)
        assertNull(model.netWorth)
        assertTrue(nativeBudgetDashboardReads("budget", listOf(fallback)).isEmpty())
        for (invalid in listOf(primary.copy(intent = ActionIntent.read), primary.copy(risk = ActionRisk.mutating),
            primary.copy(confidence = Confidence.low), primary.copy(binding = primary.binding.copy(
                pathParameters = listOf(HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField)))))) {
            assertTrue(nativeBudgetDashboardReads("budget", listOf(invalid)).isEmpty())
        }
    }
    private fun accountsListAction() = ActionSpec(
        id = "accounts.list", label = "Accounts", resourceId = "accounts",
        binding = dev.obiente.nextcloudnative.nativeui.model.ApiBinding(HttpMethod.GET,
            "/ocs/v2.php/apps/budget/api/v1/accounts", "listAccounts"),
        intent = ActionIntent.list, risk = ActionRisk.readOnly, requiresConfirmation = false,
        confidence = Confidence.verified,
    )
    private fun budgetRead(id: String, resourceId: String, path: String) = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(HttpMethod.GET, path),
        confidence = Confidence.verified,
    )
}
