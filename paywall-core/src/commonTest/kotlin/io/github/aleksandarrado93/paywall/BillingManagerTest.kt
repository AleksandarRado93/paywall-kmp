package io.github.aleksandarrado93.paywall

import io.github.aleksandarrado93.paywall.store.BillingErrorCode
import io.github.aleksandarrado93.paywall.store.BillingException
import io.github.aleksandarrado93.paywall.store.FakeStoreClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerTest {

    private val store = FakeStoreClient(products = testProducts)
    private val crashReporter = RecordingCrashReporter()

    private fun TestScope.manager(config: BillingConfig = testConfig) =
        BillingManager(config, store, crashReporter, scope = storeScope())

    @Test
    fun currentOfferingContainsTheConfiguredPlans() = runTest {
        val offering = manager().getCurrentOffering()!!

        assertEquals("default", offering.identifier)
        assertEquals(MONTHLY_ID, offering.monthly?.storeProduct?.id)
        assertEquals(SIX_MONTH_ID, offering.packageOf(PackageType.SIX_MONTH)?.identifier)
        assertNull(offering.annual)
    }

    @Test
    fun namedOfferingIsIndependentOfTheCurrentOne() = runTest {
        val gift = manager().getOffering("gift_offer")!!

        assertEquals(GIFT_ID, gift.sixMonth?.storeProduct?.id)
        assertNull(manager().getOffering("missing"))
    }

    @Test
    fun productsMissingFromTheStoreAreLeftOut() = runTest {
        store.products = testProducts.filter { it.id == MONTHLY_ID }

        val offering = manager().getCurrentOffering()!!

        assertEquals(listOf(PackageType.MONTHLY), offering.availablePackages.map { it.packageType })
        assertNull(manager().getOffering("gift_offer"))
    }

    @Test
    fun storeFailureGivesNoOfferingAndIsReported() = runTest {
        store.failure = BillingException(BillingErrorCode.NETWORK_ERROR, "offline")

        assertNull(manager().getCurrentOffering())
        assertEquals(1, crashReporter.errors.size)
    }

    @Test
    fun emptyProductResultIsRetriedOnTheNextCall() = runTest {
        val manager = manager()
        store.products = emptyList()
        assertNull(manager.getCurrentOffering())

        store.products = testProducts
        assertEquals(2, manager.getCurrentOffering()?.availablePackages?.size)
    }

    @Test
    fun purchaseMakesThePremiumProductActive() = runTest {
        val manager = manager()
        val pkg = manager.getCurrentOffering()!!.monthly!!

        val result = manager.purchase(pkg)

        assertIs<PurchaseResult.Success>(result)
        assertTrue(result.customerInfo.isPremium)
        assertEquals("premium", manager.customerInfo.value?.activePremiumProductId)
    }

    @Test
    fun purchaseOfANonPremiumProductDoesNotGrantPremium() = runTest {
        val manager = manager(testConfig.copy(premiumProductIds = setOf("other")))
        val pkg = manager.getCurrentOffering()!!.monthly!!

        val result = manager.purchase(pkg)

        assertIs<PurchaseResult.Success>(result)
        assertFalse(result.customerInfo.isPremium)
    }

    @Test
    fun cancelAndPendingAreNotErrors() = runTest {
        val manager = manager()
        val pkg = manager.getCurrentOffering()!!.monthly!!

        store.nextPurchase = FakeStoreClient.NextPurchase.Cancel
        assertEquals(PurchaseResult.Cancelled, manager.purchase(pkg))

        store.nextPurchase = FakeStoreClient.NextPurchase.Pend
        assertEquals(PurchaseResult.Pending, manager.purchase(pkg))
    }

    @Test
    fun declinedPaymentThrows() = runTest {
        val manager = manager()
        val pkg = manager.getCurrentOffering()!!.monthly!!
        store.nextPurchase = FakeStoreClient.NextPurchase.Fail(BillingErrorCode.PAYMENT_DECLINED, "Declined")

        val error = assertFailsWith<BillingException> { manager.purchase(pkg) }
        assertEquals(BillingErrorCode.PAYMENT_DECLINED, error.code)
    }

    @Test
    fun storeUpdatesRefreshCustomerInfo() = runTest {
        val manager = manager()
        manager.start()
        advanceUntilIdle()
        assertFalse(manager.customerInfo.value!!.isPremium)

        // e.g. a pending payment completed, or a purchase on another device
        store.owned += transaction("premium")
        store.emitUpdate()
        advanceUntilIdle()
        assertTrue(manager.customerInfo.value!!.isPremium)

        // e.g. a refund
        store.owned.clear()
        store.emitUpdate()
        advanceUntilIdle()
        assertFalse(manager.customerInfo.value!!.isPremium)
    }

    @Test
    fun restoreFindsExistingPurchases() = runTest {
        store.owned += transaction("premium")

        assertTrue(manager().restorePurchases().isPremium)
    }

    @Test
    fun premiumIdsMatchWithoutTheBasePlan() {
        val config = testConfig.copy(premiumProductIds = setOf("premium"))

        assertTrue(config.grantsPremium("premium"))
        assertTrue(config.grantsPremium("premium:monthly"))
        assertFalse(config.grantsPremium("other"))
    }
}
