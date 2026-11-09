package io.github.aleksandarrado93.paywall

import io.github.aleksandarrado93.paywall.bridges.PaywallAnalyticsBridge
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.bridges.PremiumStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

const val MONTHLY_ID = "premium:monthly"
const val SIX_MONTH_ID = "premium:six-month"
const val GIFT_ID = "premium:gift"

val testConfig = BillingConfig(
    offerings = listOf(
        OfferingConfig(
            identifier = "default",
            products = mapOf(PackageType.MONTHLY to MONTHLY_ID, PackageType.SIX_MONTH to SIX_MONTH_ID),
        ),
        OfferingConfig(identifier = "gift_offer", products = mapOf(PackageType.SIX_MONTH to GIFT_ID)),
    ),
)

/**
 * Scope for [BillingManager]'s store listener: runs on the test scheduler (so
 * `advanceUntilIdle` drives it) without being a child that `runTest` waits for.
 */
fun TestScope.storeScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

fun product(id: String, micros: Long, trial: FreeTrial? = null) = StoreProduct(
    id = id,
    price = Price(micros, "USD", "$${micros / 1_000_000}.99"),
    period = Period(1, PeriodUnit.MONTH),
    freeTrial = trial,
)

val testProducts = listOf(
    product(MONTHLY_ID, 9_990_000),
    product(
        SIX_MONTH_ID,
        39_990_000,
        trial = FreeTrial(Period(3, PeriodUnit.DAY), Price(39_990_000, "USD", "$39.99"), Period(6, PeriodUnit.MONTH)),
    ),
    product(GIFT_ID, 19_990_000),
)

fun transaction(productId: String) = StoreTransaction(
    productId = productId,
    transactionId = "t-$productId",
    purchaseTimeMillis = 0L,
    expirationTimeMillis = null,
)

class FakePremiumRepository : PremiumStateRepository {
    override val isPremium = MutableStateFlow(false)
    override suspend fun setPremium(isPremium: Boolean) {
        this.isPremium.value = isPremium
    }
}

class RecordingAnalytics : PaywallAnalyticsBridge {
    val conversions = mutableListOf<String>()
    val failures = mutableListOf<String>()
    override fun subscriptionConverted(plan: String) {
        conversions += plan
    }

    override fun purchaseFailed(errorType: String) {
        failures += errorType
    }
}

class RecordingCrashReporter : PaywallCrashReporterBridge {
    val errors = mutableListOf<Throwable>()
    override fun logNonFatal(throwable: Throwable) {
        errors += throwable
    }
}
