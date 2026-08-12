package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestinationSection
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

/**
 * Stable, product-facing semantics for the Budget app's verified API resources.
 *
 * The signed contract remains the authority for which routes exist. This layer only turns those
 * exact resource identities into the task order and language Budget uses in its web interface;
 * it never creates a route or guesses a request parameter.
 */
internal data class NativeBudgetDestinationSemantics(
    val label: String,
    val supportingText: String,
    val section: NextcloudCollectionDestinationSection,
    val order: Int,
)

internal fun nativeBudgetDestinationSemantics(
    appId: String,
    resourceId: String,
): NativeBudgetDestinationSemantics? {
    if (!appId.equals("budget", ignoreCase = true)) return null
    return budgetDestinationSemantics[resourceId.normalizedBudgetResourceId()]
}

internal fun isNativeBudgetApp(appId: String): Boolean = appId.equals("budget", ignoreCase = true)

internal const val NATIVE_BUDGET_DASHBOARD_VIEW_ID = "nextcloud-native.budget.dashboard"
internal const val NATIVE_BUDGET_PLAN_VIEW_ID = "nextcloud-native.budget.plan"

internal fun nativeBudgetDashboardReadDestinations(
    appId: String,
    destinations: List<DynamicNavigationDestination>,
): List<DynamicNavigationDestination> {
    if (!isNativeBudgetApp(appId)) return emptyList()
    val desiredResources = listOf(
        "accounts", "transactions", "categories", "recurring-budgets", "bills",
        "savings-goals", "debts", "pensions", "alerts", "trends",
    )
    return desiredResources.mapNotNull { resourceId ->
        destinations.firstOrNull { destination ->
            destination.resourceId.normalizedBudgetResourceId() == resourceId
        }
    }.distinctBy(DynamicNavigationDestination::actionId)
}

internal enum class NativeBudgetDashboardDataKind {
    AccountSummary,
    ReportSummary,
    BillSummary,
    IncomeSummary,
    PensionSummary,
    AlertSummary,
    ForecastTrends,
    DebtSummary,
    AssetSummary,
    Accounts,
    RecentTransactions,
    UpcomingBills,
    BudgetProgress,
    SavingsGoals,
    Alerts,
    NetWorthHistory,
}

internal data class NativeBudgetDashboardRead(
    val kind: NativeBudgetDashboardDataKind,
    val action: DynamicAction,
    val values: Map<String, String> = emptyMap(),
)

internal fun nativeBudgetDashboardReads(
    appId: String,
    actions: List<DynamicAction>,
): List<NativeBudgetDashboardRead> {
    if (!isNativeBudgetApp(appId)) return emptyList()
    val desired = listOf(
        Triple(NativeBudgetDashboardDataKind.AccountSummary, "/api/accounts/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.ReportSummary, "/api/reports/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.BillSummary, "/api/bills/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.IncomeSummary, "/api/recurring-income/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.PensionSummary, "/api/pensions/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.AlertSummary, "/api/alerts/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.ForecastTrends, "/api/forecast/trends", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.DebtSummary, "/api/debts/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.AssetSummary, "/api/assets/summary", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.Accounts, "/api/accounts", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.RecentTransactions, "/api/transactions", mapOf("limit" to "5")),
        Triple(NativeBudgetDashboardDataKind.UpcomingBills, "/api/bills/upcoming", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.BudgetProgress, "/api/reports/budget", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.SavingsGoals, "/api/savings-goals", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.Alerts, "/api/alerts", emptyMap()),
        Triple(NativeBudgetDashboardDataKind.NetWorthHistory, "/api/net-worth/snapshots", mapOf("days" to "30")),
    )
    return desired.mapNotNull { (kind, suffix, values) ->
        actions.firstOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.binding.path.endsWith(suffix) &&
                action.binding.pathParameters.none { it.required } &&
                action.binding.queryParameters.none { it.required }
        }?.let { action -> NativeBudgetDashboardRead(kind, action, values) }
    }
}

internal fun NativeAppSchema.withNativeBudgetDashboard(
    descriptorActions: List<DynamicAction> = emptyList(),
): NativeAppSchema {
    if (!isNativeBudgetApp(app.id) || views.any { it.id == NATIVE_BUDGET_DASHBOARD_VIEW_ID }) return this
    val accounts = views.firstOrNull { view ->
        view.resourceId.normalizedBudgetResourceId() == "accounts" &&
            view.component != NativeComponent.form &&
            view.sourceActionId.isNotBlank()
    } ?: return this
    val budgetReport = actions.firstOrNull { action ->
        action.binding.method == HttpMethod.GET &&
            action.binding.path.endsWith("/api/reports/budget") &&
            action.binding.requiredPathParameterNames.isEmpty() &&
            action.binding.requiredQueryParameterNames.isEmpty() &&
            action.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.read &&
            action.risk == dev.obiente.nextcloudnative.nativeui.model.ActionRisk.readOnly &&
            action.confidence in setOf(Confidence.high, Confidence.verified)
    }
    val dynamicBudgetReport = descriptorActions.firstOrNull { action ->
        action.binding.method == HttpMethod.GET &&
            action.binding.path.endsWith("/api/reports/budget") &&
            action.binding.pathParameters.none { parameter -> parameter.required } &&
            action.binding.queryParameters.none { parameter -> parameter.required } &&
            action.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.read &&
            action.risk == dev.obiente.nextcloudnative.nativeui.model.ActionRisk.readOnly &&
            action.confidence in setOf(Confidence.high, Confidence.verified)
    }
    val budgetReportActionId = budgetReport?.id ?: dynamicBudgetReport?.id
    val budgetReportConfidence = budgetReport?.confidence ?: dynamicBudgetReport?.confidence
    val budgetPlanResource = budgetReportConfidence?.let { confidence ->
        ResourceSpec(
            id = "budget",
            name = "Budget",
            confidence = confidence,
        )
    }
    return copy(
        resources = resources + listOfNotNull(
            budgetPlanResource?.takeUnless { resource -> resources.any { it.id == resource.id } },
        ),
        actions = actions.map { action ->
            if (
                action.resourceId.normalizedBudgetResourceId() == "transactions" &&
                action.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.create
            ) {
                action.copy(label = "Add transaction")
            } else action
        },
        views = listOf(
            ViewSpec(
                id = NATIVE_BUDGET_DASHBOARD_VIEW_ID,
                title = "Dashboard",
                resourceId = accounts.resourceId,
                component = NativeComponent.dashboard,
                sourceActionId = accounts.sourceActionId,
                confidence = Confidence.verified,
            ),
        ) + listOfNotNull(
            budgetReportActionId?.let { actionId ->
                ViewSpec(
                    id = NATIVE_BUDGET_PLAN_VIEW_ID,
                    title = "Budget",
                    resourceId = "budget",
                    component = NativeComponent.detail,
                    sourceActionId = actionId,
                    confidence = requireNotNull(budgetReportConfidence),
                )
            },
        ) + views,
    )
}

private fun String.normalizedBudgetResourceId(): String =
    lowercase().substringAfterLast('/').replace('_', '-')

private val budgetDestinationSemantics = mapOf(
    "accounts" to NativeBudgetDestinationSemantics(
        "Accounts", "Balances and financial accounts", NextcloudCollectionDestinationSection.Primary, 10,
    ),
    "transactions" to NativeBudgetDestinationSemantics(
        "Transactions", "Search, review and categorize activity", NextcloudCollectionDestinationSection.Primary, 20,
    ),
    "categories" to NativeBudgetDestinationSemantics(
        "Categories", "Organize income and spending", NextcloudCollectionDestinationSection.Primary, 30,
    ),
    "budget" to NativeBudgetDestinationSemantics(
        "Budget", "Plan category limits and track progress", NextcloudCollectionDestinationSection.Primary, 40,
    ),
    "recurring-budgets" to NativeBudgetDestinationSemantics(
        "Recurring budgets", "Recurring category limit data", NextcloudCollectionDestinationSection.Manage, 41,
    ),
    "recurring-income" to NativeBudgetDestinationSemantics(
        "Income", "Track expected recurring income", NextcloudCollectionDestinationSection.Primary, 50,
    ),
    "bills" to NativeBudgetDestinationSemantics(
        "Bills", "Upcoming and recurring payments", NextcloudCollectionDestinationSection.Primary, 60,
    ),
    "settlements" to NativeBudgetDestinationSemantics(
        "Shared expenses", "Balances and settlements with others", NextcloudCollectionDestinationSection.Primary, 70,
    ),
    "contacts" to NativeBudgetDestinationSemantics(
        "People", "People used by shared expenses", NextcloudCollectionDestinationSection.Primary, 75,
    ),
    "savings-goals" to NativeBudgetDestinationSemantics(
        "Savings goals", "Targets, progress and forecasts", NextcloudCollectionDestinationSection.Primary, 80,
    ),
    "debts" to NativeBudgetDestinationSemantics(
        "Debt payoff", "Balances and payoff planning", NextcloudCollectionDestinationSection.Primary, 90,
    ),
    "pensions" to NativeBudgetDestinationSemantics(
        "Pensions", "Contributions and projections", NextcloudCollectionDestinationSection.Primary, 100,
    ),
    "trends" to NativeBudgetDestinationSemantics(
        "Forecast", "Trends and forward-looking totals", NextcloudCollectionDestinationSection.Primary, 110,
    ),
    "saved" to NativeBudgetDestinationSemantics(
        "Saved reports", "Open saved finance reports", NextcloudCollectionDestinationSection.Primary, 120,
    ),
    "assets" to NativeBudgetDestinationSemantics(
        "Assets", "Valuations included in net worth", NextcloudCollectionDestinationSection.Primary, 130,
    ),
    "budget-snapshots" to NativeBudgetDestinationSemantics(
        "Budget history", "Review saved budget periods", NextcloudCollectionDestinationSection.Primary, 140,
    ),
    "alerts" to NativeBudgetDestinationSemantics(
        "Budget alerts", "Review limits that need attention", NextcloudCollectionDestinationSection.Primary, 150,
    ),
    "unrecorded-payments" to NativeBudgetDestinationSemantics(
        "Unrecorded payments", "Review expected payments not yet recorded", NextcloudCollectionDestinationSection.Primary, 160,
    ),
    "duplicates" to NativeBudgetDestinationSemantics(
        "Duplicate review", "Find possible duplicate transactions", NextcloudCollectionDestinationSection.Manage, 210,
    ),
    "scan-matches" to NativeBudgetDestinationSemantics(
        "Import matches", "Review matches found during import", NextcloudCollectionDestinationSection.Manage, 220,
    ),
    "suggestions" to NativeBudgetDestinationSemantics(
        "Suggestions", "Review automatic finance suggestions", NextcloudCollectionDestinationSection.Manage, 230,
    ),
    "tag-sets" to NativeBudgetDestinationSemantics(
        "Tags and rules", "Manage reusable transaction organization", NextcloudCollectionDestinationSection.Manage, 240,
    ),
    "banking-institutions" to NativeBudgetDestinationSemantics(
        "Banking institutions", "Manage institution metadata", NextcloudCollectionDestinationSection.Manage, 250,
    ),
    "report-mutes" to NativeBudgetDestinationSemantics(
        "Report exclusions", "Manage items excluded from reports", NextcloudCollectionDestinationSection.Manage, 260,
    ),
    "balances" to NativeBudgetDestinationSemantics(
        "Balance data", "Inspect account balance history", NextcloudCollectionDestinationSection.Manage, 270,
    ),
    "progress" to NativeBudgetDestinationSemantics(
        "Progress data", "Inspect calculated planning progress", NextcloudCollectionDestinationSection.Manage, 280,
    ),
    "snapshots" to NativeBudgetDestinationSemantics(
        "Finance snapshots", "Inspect generated finance snapshots", NextcloudCollectionDestinationSection.Manage, 290,
    ),
    "transaction-counts" to NativeBudgetDestinationSemantics(
        "Transaction counts", "Inspect aggregate transaction counts", NextcloudCollectionDestinationSection.Manage, 300,
    ),
    "transaction-ids" to NativeBudgetDestinationSemantics(
        "Transaction index", "Inspect the verified transaction index", NextcloudCollectionDestinationSection.Manage, 310,
    ),
    "years" to NativeBudgetDestinationSemantics(
        "Available years", "Inspect reporting periods", NextcloudCollectionDestinationSection.Manage, 320,
    ),
    "status" to NativeBudgetDestinationSemantics(
        "Budget status", "Check the app's finance data status", NextcloudCollectionDestinationSection.Manage, 330,
    ),
    "debt-scenarios" to NativeBudgetDestinationSemantics(
        "Debt scenarios", "Compare payoff scenarios", NextcloudCollectionDestinationSection.Manage, 340,
    ),
)

/**
 * Keeps the richer verified budget report as the user-facing planning destination when available.
 * The recurring-budget collection remains a supported fallback for older server contracts.
 */
internal fun nativeBudgetVisibleRootResourceIds(
    appId: String,
    resourceIds: List<String>,
): Set<String> {
    if (!isNativeBudgetApp(appId)) return resourceIds.toSet()
    val hasBudgetReport = resourceIds.any { it.normalizedBudgetResourceId() == "budget" }
    return resourceIds.filterNot { resourceId ->
        hasBudgetReport && resourceId.normalizedBudgetResourceId() == "recurring-budgets"
    }.toSet()
}
