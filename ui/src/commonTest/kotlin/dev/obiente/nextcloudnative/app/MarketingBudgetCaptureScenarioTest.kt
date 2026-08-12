package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.NativeFinancialAccountKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCategoryKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeCategoryPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeBudgetPlanPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFinancePresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFinancialAccountPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingBudgetCaptureScenarioTest {
    @Test
    fun `budget dynamic visual QA covers each audited view on desktop and phone`() {
        val scenarios = listOf(
            MarketingCaptureScenario.BudgetTransactionsDesktop,
            MarketingCaptureScenario.BudgetTransactionsMobile,
            MarketingCaptureScenario.BudgetAccountsDesktop,
            MarketingCaptureScenario.BudgetAccountsMobile,
            MarketingCaptureScenario.BudgetCategoriesDesktop,
            MarketingCaptureScenario.BudgetCategoriesMobile,
            MarketingCaptureScenario.BudgetPlanDesktop,
            MarketingCaptureScenario.BudgetPlanMobile,
        )

        assertEquals(
            setOf(NextcloudPresentation.Desktop, NextcloudPresentation.Adaptive),
            scenarios.map(MarketingCaptureScenario::presentation).toSet(),
        )
        assertTrue(scenarios.filter { it.presentation == NextcloudPresentation.Desktop }.all {
            it.width == 1_440 && it.height == 900
        })
        assertTrue(scenarios.filter { it.presentation == NextcloudPresentation.Adaptive }.all {
            it.width == 1_080 && it.height == 1_800 && it.density > 1f
        })
    }

    @Test
    fun `budget fixtures use verified upstream collection routes with synthetic records`() {
        val accounts = assertNotNull(marketingBudgetSchema.action("route-account-index"))
        val transactions = assertNotNull(marketingBudgetSchema.action("route-transaction-index"))
        val categories = assertNotNull(marketingBudgetSchema.action("route-category-index"))
        val budget = assertNotNull(marketingBudgetSchema.action("route-report-budget"))

        assertEquals(HttpMethod.GET, accounts.binding.method)
        assertEquals("/apps/budget/api/accounts", accounts.binding.path)
        assertEquals(HttpMethod.GET, transactions.binding.method)
        assertEquals("/apps/budget/api/transactions", transactions.binding.path)
        assertEquals(HttpMethod.GET, categories.binding.method)
        assertEquals("/apps/budget/api/categories", categories.binding.path)
        assertEquals(HttpMethod.GET, budget.binding.method)
        assertEquals("/apps/budget/api/reports/budget", budget.binding.path)
        assertEquals(
            "/apps/budget/api/categories/tree",
            assertNotNull(marketingBudgetSchema.action("route-category-tree")).binding.path,
        )
        assertEquals(
            "/apps/budget/api/categories/transaction-counts",
            assertNotNull(marketingBudgetSchema.action("route-category-transaction-counts")).binding.path,
        )
        assertEquals(
            "/apps/budget/api/categories/report-mutes",
            assertNotNull(marketingBudgetSchema.action("route-category-report-mutes")).binding.path,
        )
        assertTrue(marketingBudgetAccountRecords.all { it.actionSafeIdentity.not() })
        assertTrue(marketingBudgetTransactionRecords.all { it.actionSafeIdentity.not() })
        assertTrue(marketingBudgetCategoryRecords.all { it.actionSafeIdentity.not() })
        assertTrue(marketingBudgetPlanRecords.all { it.actionSafeIdentity.not() })
    }

    @Test
    fun `budget fixtures drive the shared semantic renderers`() {
        val accounts = marketingBudgetAccountRecords.map { record ->
            assertNotNull(nativeFinancialAccountPresentation(marketingBudgetAccounts, record))
        }
        val transactions = marketingBudgetTransactionRecords.map { record ->
            assertNotNull(nativeFinancePresentation(marketingBudgetTransactions, record))
        }
        val categories = marketingBudgetCategoryRecords.map { record ->
            assertNotNull(nativeCategoryPresentation(marketingBudgetCategories, record))
        }
        val plan = assertNotNull(nativeBudgetPlanPresentation(marketingBudgetPlanRecords.single()))

        assertTrue(accounts.any { it.kind == NativeFinancialAccountKind.Asset })
        assertTrue(accounts.any { it.kind == NativeFinancialAccountKind.Liability })
        assertTrue(accounts.any { it.convertedBalance != null })
        assertTrue(transactions.any { it.amount < 0.0 })
        assertTrue(transactions.any { it.amount > 0.0 })
        assertTrue(categories.any { it.kind == NativeCategoryKind.Expense })
        assertTrue(categories.any { it.kind == NativeCategoryKind.Income })
        assertTrue(categories.any { it.parentId != null })
        assertTrue(categories.any { it.shared && !it.writable && it.mutedFromReports })
        assertEquals(2630.0, plan.budgeted)
        assertEquals(2399.95, plan.spent)
        assertEquals(5, plan.categories.size)
        assertEquals("Leisure", plan.categories.first().name)
        assertTrue(plan.categories.any { it.carried == 40.0 })
    }
}
