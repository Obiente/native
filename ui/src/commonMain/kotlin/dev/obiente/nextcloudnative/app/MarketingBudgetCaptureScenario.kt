package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
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
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeFinanceCurrency
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor

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

internal val marketingBudgetAccounts = ResourceSpec(
    id = "accounts",
    name = "Accounts",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = true),
        FieldSpec("balance", "Balance", FieldKind.currency, required = true, readOnly = true, format = "EUR"),
        FieldSpec("currency", "Currency", FieldKind.string, required = true, readOnly = true),
        FieldSpec("type", "Account type", FieldKind.enumeration, required = true, readOnly = true),
        FieldSpec("institution", "Institution", FieldKind.string, required = false, readOnly = true),
        FieldSpec("accountNumber", "Account number", FieldKind.string, required = false, readOnly = true),
        FieldSpec("lastReconciled", "Last reconciled", FieldKind.dateTime, required = false, readOnly = true),
        FieldSpec("convertedBalance", "Converted balance", FieldKind.currency, required = false, readOnly = true, format = "EUR"),
        FieldSpec("baseCurrency", "Base currency", FieldKind.string, required = false, readOnly = true),
        FieldSpec("excludedFromReports", "Excluded from reports", FieldKind.boolean, required = false, readOnly = true),
    ),
)
internal val marketingBudgetTransactions = ResourceSpec(
    id = "transactions",
    name = "Transactions",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("description", "Description", FieldKind.string, required = true, readOnly = true),
        FieldSpec("amount", "Amount", FieldKind.currency, required = true, readOnly = true, format = "EUR"),
        FieldSpec("currency", "Currency", FieldKind.string, required = true, readOnly = true),
        FieldSpec("type", "Type", FieldKind.enumeration, required = true, readOnly = true),
        FieldSpec("date", "Date", FieldKind.date, required = true, readOnly = true),
        FieldSpec("categoryName", "Category", FieldKind.string, required = false, readOnly = true),
        FieldSpec("accountName", "Account", FieldKind.string, required = false, readOnly = true),
    ),
)
internal val marketingBudgetCategories = ResourceSpec(
    id = "categories",
    name = "Categories",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = true),
        FieldSpec("type", "Type", FieldKind.enumeration, required = true, readOnly = true),
        FieldSpec("parentId", "Parent", FieldKind.integer, required = false, readOnly = true),
        FieldSpec("icon", "Icon", FieldKind.string, required = false, readOnly = true),
        FieldSpec("color", "Color", FieldKind.string, required = false, readOnly = true),
        FieldSpec("budgetAmount", "Budget", FieldKind.currency, required = false, readOnly = true, format = "EUR"),
        FieldSpec("period", "Period", FieldKind.string, required = false, readOnly = true),
        FieldSpec("transactionCount", "Transactions", FieldKind.integer, required = false, readOnly = true),
        FieldSpec("_shared", "Shared", FieldKind.boolean, required = false, readOnly = true),
        FieldSpec("_sharedByName", "Shared by", FieldKind.string, required = false, readOnly = true),
        FieldSpec("_canWrite", "Can edit", FieldKind.boolean, required = false, readOnly = true),
        FieldSpec("excludedFromReports", "Excluded from reports", FieldKind.boolean, required = false, readOnly = true),
    ),
)
private val marketingBudgetBudgets = budgetResource("recurring-budgets", "Budgets", "amount", "Budgeted")
private val marketingBudgetBills = budgetResource("bills", "Bills", "amount", "Amount")
private val marketingBudgetGoals = budgetResource("savings-goals", "Savings goals", "current", "Saved")

private val marketingBudgetAccountsView = ViewSpec(
    "route-account-index",
    "Accounts",
    "accounts",
    NativeComponent.collectionList,
    "route-account-index",
    Confidence.verified,
)
private val marketingBudgetTransactionsView = ViewSpec(
    "route-transaction-index",
    "Transactions",
    "transactions",
    NativeComponent.collectionList,
    "route-transaction-index",
    Confidence.verified,
)
private val marketingBudgetCategoriesView = ViewSpec(
    "route-category-index",
    "Categories",
    "categories",
    NativeComponent.collectionList,
    "route-category-index",
    Confidence.verified,
)

private fun marketingBudgetCollectionRead(
    id: String,
    resourceId: String,
    path: String,
): ActionSpec = ActionSpec(
    id = id,
    label = "Load $resourceId",
    resourceId = resourceId,
    binding = ApiBinding(
        method = HttpMethod.GET,
        path = path,
        operationId = id,
    ),
    intent = ActionIntent.read,
    risk = ActionRisk.readOnly,
    requiresConfirmation = false,
    confidence = Confidence.verified,
)

internal val marketingBudgetSchema = NativeAppSchema(
    schemaVersion = "visual-qa",
    app = AppIdentity("budget", "Budget", "2.39.1"),
    confidence = Confidence.verified,
    resources = listOf(
        marketingBudgetAccounts,
        marketingBudgetTransactions,
        marketingBudgetCategories,
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
        marketingBudgetAccountsView,
        marketingBudgetTransactionsView,
        marketingBudgetCategoriesView,
    ),
    actions = listOf(
        marketingBudgetCollectionRead(
            "route-account-index",
            "accounts",
            "/apps/budget/api/accounts",
        ),
        marketingBudgetCollectionRead(
            "route-transaction-index",
            "transactions",
            "/apps/budget/api/transactions",
        ),
        marketingBudgetCollectionRead(
            "route-category-index",
            "categories",
            "/apps/budget/api/categories",
        ),
        marketingBudgetCollectionRead(
            "route-category-tree",
            "categories",
            "/apps/budget/api/categories/tree",
        ),
        marketingBudgetCollectionRead(
            "route-category-transaction-counts",
            "categories",
            "/apps/budget/api/categories/transaction-counts",
        ),
        marketingBudgetCollectionRead(
            "route-category-report-mutes",
            "categories",
            "/apps/budget/api/categories/report-mutes",
        ),
    ),
)

private fun budgetRecords(vararg values: Triple<String, String, Double>): List<NativeRecord> =
    values.map { (id, name, amount) ->
        NativeRecord(id, mapOf("name" to name, "balance" to amount.toString(), "amount" to amount.toString(), "current" to amount.toString()))
    }

internal val marketingBudgetAccountRecords = listOf(
    NativeRecord("1", mapOf("name" to "Daily banking", "balance" to "2840.25", "currency" to "EUR", "type" to "checking", "institution" to "Northstar Bank", "accountNumber" to "Ending 4821", "lastReconciled" to "2026-08-01T18:30:00Z")),
    NativeRecord("2", mapOf("name" to "Emergency savings", "balance" to "6250.00", "currency" to "EUR", "type" to "savings", "institution" to "Northstar Bank", "accountNumber" to "Ending 7340", "lastReconciled" to "2026-07-31T08:15:00Z")),
    NativeRecord("3", mapOf("name" to "Travel card", "balance" to "-684.40", "currency" to "EUR", "type" to "credit_card", "institution" to "Waypoint Credit", "accountNumber" to "Ending 1088", "lastReconciled" to "2026-07-28T20:00:00Z")),
    NativeRecord("4", mapOf("name" to "Index portfolio", "balance" to "4110.80", "currency" to "USD", "type" to "investment", "institution" to "Common Ground", "convertedBalance" to "3527.61", "baseCurrency" to "EUR")),
).map { record -> record.copy(actionSafeIdentity = false) }

internal val marketingBudgetTransactionRecords = listOf(
    NativeRecord("1", mapOf("description" to "Weekly groceries", "amount" to "86.20", "currency" to "EUR", "type" to "debit", "date" to "2026-08-10", "categoryName" to "Groceries", "accountName" to "Daily banking")),
    NativeRecord("2", mapOf("description" to "August salary", "amount" to "3250.00", "currency" to "EUR", "type" to "credit", "date" to "2026-08-09", "categoryName" to "Income", "accountName" to "Daily banking")),
    NativeRecord("3", mapOf("description" to "Electricity", "amount" to "142.70", "currency" to "EUR", "type" to "debit", "date" to "2026-08-08", "categoryName" to "Utilities", "accountName" to "Daily banking")),
    NativeRecord("4", mapOf("description" to "Train tickets", "amount" to "48.50", "currency" to "EUR", "type" to "debit", "date" to "2026-08-07", "categoryName" to "Transport", "accountName" to "Travel card")),
    NativeRecord("5", mapOf("description" to "Returned deposit", "amount" to "75.00", "currency" to "EUR", "type" to "credit", "date" to "2026-08-06", "categoryName" to "Refunds", "accountName" to "Daily banking")),
).map { record -> record.copy(actionSafeIdentity = false) }

internal val marketingBudgetCategoryRecords = listOf(
    NativeRecord("10", mapOf("name" to "Food", "type" to "expense", "icon" to "food", "color" to "#D35D6E", "budgetAmount" to "520.00", "period" to "monthly", "transactionCount" to "38")),
    NativeRecord("11", mapOf("name" to "Groceries", "type" to "expense", "parentId" to "10", "icon" to "cart", "color" to "#D35D6E", "transactionCount" to "24")),
    NativeRecord("12", mapOf("name" to "Restaurants", "type" to "expense", "parentId" to "10", "icon" to "food", "color" to "#D35D6E", "transactionCount" to "14")),
    NativeRecord("20", mapOf("name" to "Home", "type" to "expense", "icon" to "home", "color" to "#4C84C4", "budgetAmount" to "1450.00", "period" to "monthly", "transactionCount" to "17")),
    NativeRecord("21", mapOf("name" to "Utilities", "type" to "expense", "parentId" to "20", "icon" to "bolt", "color" to "#4C84C4", "transactionCount" to "9")),
    NativeRecord("30", mapOf("name" to "Salary", "type" to "income", "icon" to "money", "color" to "#4AA96C", "transactionCount" to "8")),
    NativeRecord("31", mapOf("name" to "Freelance", "type" to "income", "icon" to "work", "color" to "#4AA96C", "transactionCount" to "5")),
    NativeRecord("40", mapOf("name" to "Shared household", "type" to "expense", "icon" to "people", "color" to "#8B6BB1", "transactionCount" to "12", "_shared" to "true", "_sharedByName" to "Morgan", "_canWrite" to "false", "excludedFromReports" to "true")),
).map { record -> record.copy(actionSafeIdentity = false) }

private val marketingBudgetRecords = mapOf(
    "accounts" to marketingBudgetAccountRecords,
    "transactions" to marketingBudgetTransactionRecords,
    "categories" to marketingBudgetCategoryRecords,
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

@Composable
internal fun MarketingBudgetDynamicWorkspaceScenario(scenario: MarketingCaptureScenario) {
    val accounts = scenario == MarketingCaptureScenario.BudgetAccountsDesktop ||
        scenario == MarketingCaptureScenario.BudgetAccountsMobile
    val categories = scenario == MarketingCaptureScenario.BudgetCategoriesDesktop ||
        scenario == MarketingCaptureScenario.BudgetCategoriesMobile
    require(
        accounts || categories || scenario == MarketingCaptureScenario.BudgetTransactionsDesktop ||
            scenario == MarketingCaptureScenario.BudgetTransactionsMobile,
    ) { "${scenario.id} is not a Budget dynamic workspace capture." }
    val desktop = scenario.presentation == dev.obiente.nextcloudnative.app.design.NextcloudPresentation.Desktop
    val selectedView = when {
        accounts -> marketingBudgetAccountsView
        categories -> marketingBudgetCategoriesView
        else -> marketingBudgetTransactionsView
    }
    val records = when {
        accounts -> marketingBudgetAccountRecords
        categories -> marketingBudgetCategoryRecords
        else -> marketingBudgetTransactionRecords
    }
    val destinations = listOf(
        NextcloudCollectionDestination(NATIVE_BUDGET_DASHBOARD_VIEW_ID, "Dashboard"),
        NextcloudCollectionDestination(marketingBudgetAccountsView.id, "Accounts"),
        NextcloudCollectionDestination(marketingBudgetTransactionsView.id, "Transactions"),
        NextcloudCollectionDestination(marketingBudgetCategoriesView.id, "Categories"),
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(destinations, selectedView.id),
        mode = if (desktop) NextcloudCollectionNavigationMode.Sidebar else NextcloudCollectionNavigationMode.Drawer,
        workspaceLabel = "Budget",
        contentTitle = selectedView.title,
        contentSubtitle = when {
            accounts -> "Balances and account health"
            categories -> "Organize income and expenses"
            else -> "Search, review and categorize activity"
        },
        onBack = {},
        hasHierarchyBack = false,
        onDestinationSelected = {},
    ) {
        CompositionLocalProvider(LocalNativeFinanceCurrency provides "EUR") {
            GenericNativeAppScreen(
                schema = marketingBudgetSchema,
                view = selectedView,
                state = NativeScreenState.Ready(records),
                actionExecutor = NativeActionExecutor {
                    NativeActionExecutionResult.Failure("Synthetic visual QA actions are disabled.")
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
