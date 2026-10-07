package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeFinancialSummaryBalanceTest {
    @Test
    fun mixedCurrenciesWithoutACommonUnitAreNeverSummed() {
        val dollars = account(100.0, "USD")
        val euros = account(50.0, "EUR")
        val accounts = listOf(dollars, euros)
        assertNull(nativeFinancialSummaryBalance(dollars, summaryCurrency = null, accounts = accounts))
        assertNull(nativeFinancialSummaryBalance(euros, summaryCurrency = null, accounts = accounts))
    }

    @Test
    fun convertedBalanceIsAlwaysCounted() {
        val euros = account(50.0, "EUR", converted = 55.0)
        assertEquals(55.0, nativeFinancialSummaryBalance(euros, null, listOf(euros, account(1.0, "USD"))))
    }

    @Test
    fun rawBalanceCountsOnlyInTheSummaryCurrency() {
        val dollars = account(100.0, "USD")
        val euros = account(50.0, "EUR")
        val unlabeled = account(10.0, null)
        val accounts = listOf(dollars, euros, unlabeled)
        assertEquals(100.0, nativeFinancialSummaryBalance(dollars, "USD", accounts))
        assertNull(nativeFinancialSummaryBalance(euros, "USD", accounts))
        assertEquals(10.0, nativeFinancialSummaryBalance(unlabeled, "USD", accounts))
    }

    @Test
    fun accountsWithoutAnyCurrencyStillSum() {
        val first = account(10.0, null)
        assertEquals(10.0, nativeFinancialSummaryBalance(first, null, listOf(first, account(5.0, null))))
    }

    private fun account(balance: Double, currency: String?, converted: Double? = null) =
        NativeFinancialAccountPresentation(
            name = "Synthetic account",
            balance = balance,
            currency = currency,
            type = null,
            kind = NativeFinancialAccountKind.Asset,
            institution = null,
            accountNumber = null,
            lastReconciled = null,
            convertedBalance = converted,
            baseCurrency = null,
            excludedFromReports = false,
        )
}
