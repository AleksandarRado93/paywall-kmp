package io.github.aleksandarrado93.paywall

import io.github.aleksandarrado93.paywall.store.BillingErrorCode
import io.github.aleksandarrado93.paywall.store.BillingException
import io.github.aleksandarrado93.paywall.store.FakeStoreClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStoreClient(products = testProducts)
    private val premium = FakePremiumRepository()
    private val analytics = RecordingAnalytics()
    private val crashReporter = RecordingCrashReporter()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): PaywallViewModel {
        val manager = BillingManager(testConfig, store, crashReporter, scope = storeScope())
        return PaywallViewModel(manager, premium, analytics, crashReporter).also { vm ->
            backgroundScope.launch { vm.uiState.collect {} }
        }
    }

    @Test
    fun plansAndTrialsLoad() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val plans = assertIs<PlansState.Ready>(vm.uiState.value.plans)
        assertEquals(2, plans.offering.availablePackages.size)
        assertEquals(setOf(SIX_MONTH_ID), vm.uiState.value.freeTrials.keys)
    }

    @Test
    fun noProductsMeansUnavailable() = runTest(dispatcher) {
        store.products = emptyList()
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(PlansState.Unavailable, vm.uiState.value.plans)
    }

    @Test
    fun successfulPurchaseSavesPremiumAndSignalsSuccess() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val success = backgroundScope.launch { vm.purchaseSuccess.first() }

        vm.purchase(PackageType.MONTHLY, planLabel = "Monthly")
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Success, vm.uiState.value.purchase)
        assertTrue(premium.isPremium.value)
        assertEquals(listOf("Monthly"), analytics.conversions)
        assertTrue(success.isCompleted)
    }

    @Test
    fun cancelReturnsToIdleWithoutAnalytics() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        store.nextPurchase = FakeStoreClient.NextPurchase.Cancel

        vm.purchase(PackageType.MONTHLY)
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Idle, vm.uiState.value.purchase)
        assertTrue(analytics.failures.isEmpty())
        assertFalse(premium.isPremium.value)
    }

    @Test
    fun declineShowsErrorAndTracksCode() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        store.nextPurchase = FakeStoreClient.NextPurchase.Fail(BillingErrorCode.PAYMENT_DECLINED, "Card declined")

        vm.purchase(PackageType.MONTHLY)
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Error("Card declined"), vm.uiState.value.purchase)
        assertEquals(listOf("PAYMENT_DECLINED"), analytics.failures)
    }

    @Test
    fun pendingPurchaseCompletesWhenTheStoreApprovesIt() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        store.nextPurchase = FakeStoreClient.NextPurchase.Pend

        vm.purchase(PackageType.MONTHLY, planLabel = "Monthly")
        advanceUntilIdle()
        assertEquals(PurchaseUiState.Pending, vm.uiState.value.purchase)
        assertFalse(premium.isPremium.value)

        store.owned += transaction("premium")
        store.emitUpdate()
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Success, vm.uiState.value.purchase)
        assertTrue(premium.isPremium.value)
        assertEquals(listOf("Monthly"), analytics.conversions)
    }

    @Test
    fun unknownPlanShowsError() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.purchase(PackageType.ANNUAL)
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Error("Product not available"), vm.uiState.value.purchase)
    }

    @Test
    fun restoreWithAPurchaseUnlocksPremium() = runTest(dispatcher) {
        store.owned += transaction("premium")
        val vm = viewModel()
        advanceUntilIdle()

        vm.restorePurchases()
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Success, vm.uiState.value.purchase)
        assertTrue(premium.isPremium.value)
    }

    @Test
    fun restoreWithoutPurchasesShowsError() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.restorePurchases()
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Error("No active subscription found"), vm.uiState.value.purchase)
    }

    @Test
    fun restoreFailureShowsError() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        store.failure = BillingException(BillingErrorCode.NETWORK_ERROR, "offline")

        vm.restorePurchases()
        advanceUntilIdle()

        assertEquals(PurchaseUiState.Error("offline"), vm.uiState.value.purchase)
    }
}
