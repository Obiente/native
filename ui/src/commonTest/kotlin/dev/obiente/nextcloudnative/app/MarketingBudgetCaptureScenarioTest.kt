package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.NativeFinancialAccountKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFinancePresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFinancialAccountPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingBudgetCaptureScenarioTest {
    @Test
    fun `budget dynamic visual QA covers accounts and transactions on desktop and phone`() {
        val scenarios = listOf(
            MarketingCaptureScenario.BudgetTransactionsDesktop,
            MarketingCaptureScenario.BudgetTransactionsMobile,
            MarketingCaptureScenario.BudgetAccountsDesktop,
            MarketingCaptureScenario.BudgetAccountsMobile,
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

        assertEquals(HttpMethod.GET, accounts.binding.method)
        assertEquals("/apps/budget/api/accounts", accounts.binding.path)
        assertEquals(HttpMethod.GET, transactions.binding.method)
        assertEquals("/apps/budget/api/transactions", transactions.binding.path)
        assertTrue(marketingBudgetAccountRecords.all { it.actionSafeIdentity.not() })
        assertTrue(marketingBudgetTransactionRecords.all { it.actionSafeIdentity.not() })
    }

    @Test
    fun `budget fixtures drive the shared semantic renderers`() {
        val accounts = marketingBudgetAccountRecords.map { record ->
            assertNotNull(nativeFinancialAccountPresentation(marketingBudgetAccounts, record))
        }
        val transactions = marketingBudgetTransactionRecords.map { record ->
            assertNotNull(nativeFinancePresentation(marketingBudgetTransactions, record))
        }

        assertTrue(accounts.any { it.kind == NativeFinancialAccountKind.Asset })
        assertTrue(accounts.any { it.kind == NativeFinancialAccountKind.Liability })
        assertTrue(accounts.any { it.convertedBalance != null })
        assertTrue(transactions.any { it.amount < 0.0 })
        assertTrue(transactions.any { it.amount > 0.0 })
    }
}
