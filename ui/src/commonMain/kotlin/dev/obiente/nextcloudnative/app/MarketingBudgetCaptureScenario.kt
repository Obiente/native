package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

private fun budgetResource(
    id: String,
    name: String,
    measureId: String,
    measureLabel: String,
): ResourceSpec = ResourceSpec(
    id = id,
    name = name,
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = true),
        FieldSpec(measureId, measureLabel, FieldKind.currency, required = true, readOnly = true),
    ),
)

private val marketingBudgetAccounts = budgetResource("accounts", "Accounts", "balance", "Balance")
private val marketingBudgetTransactions = budgetResource("transactions", "Transactions", "amount", "Amount")
private val marketingBudgetBudgets = budgetResource("recurring-budgets", "Budgets", "amount", "Budgeted")
private val marketingBudgetBills = budgetResource("bills", "Bills", "amount", "Amount")
private val marketingBudgetGoals = budgetResource("savings-goals", "Savings goals", "current", "Saved")

private val marketingBudgetSchema = NativeAppSchema(
    schemaVersion = "visual-qa",
    app = AppIdentity("budget", "Budget", "2.39.1"),
    confidence = Confidence.verified,
    resources = listOf(
        marketingBudgetAccounts,
        marketingBudgetTransactions,
        marketingBudgetBudgets,
        marketingBudgetBills,
        marketingBudgetGoals,
    ),
    views = listOf(
        ViewSpec(
            NATIVE_BUDGET_DASHBOARD_VIEW_ID,
            "Dashboard",
            "accounts",
            NativeComponent.dashboard,
            "accounts.list",
            Confidence.verified,
        ),
    ),
)

private fun budgetRecords(vararg values: Triple<String, String, Double>): List<NativeRecord> =
    values.map { (id, name, amount) ->
        NativeRecord(id, mapOf("name" to name, "balance" to amount.toString(), "amount" to amount.toString(), "current" to amount.toString()))
    }

private val marketingBudgetRecords = mapOf(
    "accounts" to budgetRecords(
        Triple("1", "Current account", 2840.25),
        Triple("2", "Savings", 6250.0),
        Triple("3", "Household", 1184.4),
    ),
    "transactions" to budgetRecords(
        Triple("1", "Groceries", -86.2),
        Triple("2", "Salary", 3250.0),
        Triple("3", "Energy", -142.7),
    ),
    "recurring-budgets" to budgetRecords(
        Triple("1", "Groceries", 520.0),
        Triple("2", "Transport", 180.0),
        Triple("3", "Leisure", 240.0),
    ),
    "bills" to budgetRecords(
        Triple("1", "Rent", 1120.0),
        Triple("2", "Energy", 142.7),
        Triple("3", "Internet", 54.0),
    ),
    "savings-goals" to budgetRecords(
        Triple("1", "Emergency fund", 4200.0),
        Triple("2", "Holiday", 1350.0),
    ),
)

private fun marketingBudgetRead(
    id: String,
    resourceId: String,
    suffix: String,
): DynamicAction = DynamicAction(
    id = id,
    label = id,
    resourceId = resourceId,
    intent = ActionIntent.read,
    risk = ActionRisk.readOnly,
    requiresConfirmation = false,
    binding = DynamicHttpBinding(HttpMethod.GET, "/apps/budget$suffix"),
    confidence = Confidence.verified,
)

private val marketingBudgetDashboardActions = listOf(
    marketingBudgetRead("account-summary", "accounts", "/api/accounts/summary"),
    marketingBudgetRead("report-summary", "reports", "/api/reports/summary"),
    marketingBudgetRead("bill-summary", "bills", "/api/bills/summary"),
    marketingBudgetRead("alert-summary", "alerts", "/api/alerts/summary"),
    marketingBudgetRead("accounts-list", "accounts", "/api/accounts"),
)

private val marketingBudgetDashboardReads = nativeBudgetDashboardReads(
    "budget",
    marketingBudgetDashboardActions,
)

private val marketingBudgetDashboardRecords = mapOf(
    "account-summary" to listOf(
        NativeRecord("summary", mapOf("netWorth" to "15322.56", "currency" to "GBP")),
    ),
    "report-summary" to listOf(
        NativeRecord("summary", mapOf("income" to "4500", "expenses" to "2295.27")),
    ),
    "bill-summary" to listOf(NativeRecord("summary", mapOf("count" to "3"))),
    "alert-summary" to listOf(NativeRecord("summary", mapOf("count" to "2"))),
    "accounts-list" to marketingBudgetRecords.getValue("accounts"),
)

@Composable
internal fun MarketingBudgetDashboardScenario(scenario: MarketingCaptureScenario) {
    val desktop = scenario.presentation == dev.obiente.nextcloudnative.app.design.NextcloudPresentation.Desktop
    val destinations = listOf(
        NextcloudCollectionDestination(NATIVE_BUDGET_DASHBOARD_VIEW_ID, "Dashboard", supportingText = "Net worth and finance overview"),
        NextcloudCollectionDestination("accounts", "Accounts"),
        NextcloudCollectionDestination("transactions", "Transactions"),
        NextcloudCollectionDestination("recurring-budgets", "Budget"),
        NextcloudCollectionDestination("bills", "Bills"),
        NextcloudCollectionDestination("savings-goals", "Savings goals"),
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(destinations, NATIVE_BUDGET_DASHBOARD_VIEW_ID),
        mode = if (desktop) NextcloudCollectionNavigationMode.Sidebar else NextcloudCollectionNavigationMode.Drawer,
        workspaceLabel = "Budget",
        contentTitle = "Dashboard",
        contentSubtitle = "Net worth and finance overview",
        onBack = {},
        hasHierarchyBack = false,
        onDestinationSelected = {},
    ) {
        NativeBudgetDashboard(
            schema = marketingBudgetSchema,
            state = NativeScreenState.Ready(marketingBudgetRecords.getValue("accounts")),
            recordsByResourceId = marketingBudgetRecords,
            dashboardReads = marketingBudgetDashboardReads,
            dashboardRecordsByActionId = marketingBudgetDashboardRecords,
        )
    }
}
