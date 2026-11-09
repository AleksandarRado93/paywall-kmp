package io.github.aleksandarrado93.paywall.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import io.github.aleksandarrado93.paywall.FreeTrial
import io.github.aleksandarrado93.paywall.Period
import io.github.aleksandarrado93.paywall.PeriodUnit
import io.github.aleksandarrado93.paywall.Price
import io.github.aleksandarrado93.paywall.StoreProduct
import io.github.aleksandarrado93.paywall.StoreTransaction
import io.github.aleksandarrado93.paywall.store.BillingErrorCode
import io.github.aleksandarrado93.paywall.store.BillingException
import io.github.aleksandarrado93.paywall.store.StoreClient
import io.github.aleksandarrado93.paywall.store.StorePurchaseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * [StoreClient] backed by Google Play Billing.
 *
 * - Acknowledges every purchase (Play refunds unacknowledged ones after 3 days),
 *   including purchases found later: pending payments that completed, or
 *   purchases made while the app was killed.
 * - Pending purchases don't count as active until Play reports them purchased.
 * - Re-checks purchases whenever the app returns to the foreground.
 *
 * Create one instance in `Application.onCreate` (it tracks the current
 * Activity, which the purchase sheet needs) and pass it to
 * [io.github.aleksandarrado93.paywall.BillingManager].
 */
class AndroidStoreClient(application: Application) : StoreClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val connectMutex = Mutex()
    private val purchaseMutex = Mutex()

    private var currentActivity = WeakReference<Activity>(null)
    private var startedActivities = 0
    private var pendingFlow: CompletableDeferred<Pair<BillingResult, List<Purchase>>>? = null
    private val productDetails = mutableMapOf<String, ProductDetails>()

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        val flow = pendingFlow
        if (flow != null && !flow.isCompleted) {
            flow.complete(result to purchases.orEmpty())
        } else {
            // Purchases finished outside a flow we started (e.g. a pending payment completed).
            scope.launch {
                purchases.orEmpty().forEach { acknowledgeIfNeeded(it) }
                updates.tryEmit(Unit)
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(application)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override val purchaseUpdates: Flow<Unit> = updates.asSharedFlow()

    init {
        application.registerActivityLifecycleCallbacks(ActivityTracker())
    }

    override fun start() {
        scope.launch { runCatching { ensureConnected() } }
    }

    override suspend fun loadProducts(productIds: List<String>): List<StoreProduct> {
        val refs = productIds.map(::ProductRef)
        val subs = queryDetails(refs.map { it.productId }.distinct(), ProductType.SUBS)
        // Ids without a base plan that aren't subscriptions may be one-time products.
        val maybeInApp = refs
            .filter { ref -> ref.basePlanId == null && subs.none { it.productId == ref.productId } }
            .map { it.productId }
            .distinct()
        val details = subs + queryDetails(maybeInApp, ProductType.INAPP)
        details.forEach { productDetails[it.productId] = it }
        return productIds.mapNotNull { id ->
            val ref = ProductRef(id)
            details.firstOrNull { it.productId == ref.productId }?.toStoreProduct(ref)
        }
    }

    override suspend fun purchase(product: StoreProduct, appAccountToken: String?): StorePurchaseOutcome =
        purchaseMutex.withLock {
            val ref = ProductRef(product.id)
            val details = productDetails[ref.productId]
                ?: queryDetails(listOf(ref.productId), ProductType.SUBS).firstOrNull()
                ?: queryDetails(listOf(ref.productId), ProductType.INAPP).firstOrNull()
                ?: throw BillingException(BillingErrorCode.PRODUCT_NOT_AVAILABLE, "Product not available")

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
            if (details.productType == ProductType.SUBS) {
                val offer = details.chooseOffer(ref.basePlanId)
                    ?: throw BillingException(BillingErrorCode.PRODUCT_NOT_AVAILABLE, "Plan not available")
                productParams.setOfferToken(offer.offerToken)
            }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams.build()))
            appAccountToken?.let { params.setObfuscatedAccountId(it) }
            if (details.productType == ProductType.SUBS) {
                // Switching plans while one is still active must replace it, or Play refuses.
                queryPurchases(ProductType.SUBS)
                    .firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    ?.let { old ->
                        params.setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(old.purchaseToken)
                                .setSubscriptionReplacementMode(
                                    BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION,
                                )
                                .build(),
                        )
                    }
            }

            val activity = currentActivity.get()
                ?: throw BillingException(BillingErrorCode.UNKNOWN, "No activity to show the purchase screen")
            val flow = CompletableDeferred<Pair<BillingResult, List<Purchase>>>()
            pendingFlow = flow
            try {
                val launch = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params.build()) }
                if (launch.responseCode != BillingResponseCode.OK) return@withLock outcomeOf(launch, emptyList(), ref)
                val (result, purchases) = flow.await()
                outcomeOf(result, purchases, ref)
            } finally {
                pendingFlow = null
            }
        }

    override suspend fun activePurchases(): List<StoreTransaction> =
        (queryPurchases(ProductType.SUBS) + queryPurchases(ProductType.INAPP))
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .onEach { acknowledgeIfNeeded(it) }
            .flatMap { it.toTransactions() }

    // Play has no separate restore call: the purchase query is always fresh from Play.
    override suspend fun restore(): List<StoreTransaction> = activePurchases()

    private suspend fun outcomeOf(
        result: BillingResult,
        purchases: List<Purchase>,
        ref: ProductRef,
    ): StorePurchaseOutcome = when (result.responseCode) {
        BillingResponseCode.OK -> {
            val purchase = purchases.firstOrNull { ref.productId in it.products }
                ?: purchases.firstOrNull()
                ?: throw BillingException(BillingErrorCode.UNKNOWN, "Play returned no purchase")
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    acknowledgeIfNeeded(purchase)
                    StorePurchaseOutcome.Purchased(purchase.toTransactions().first())
                }
                else -> StorePurchaseOutcome.Pending
            }
        }
        BillingResponseCode.USER_CANCELED -> StorePurchaseOutcome.Cancelled
        BillingResponseCode.ITEM_ALREADY_OWNED -> {
            // Already bought (e.g. on another device): treat it as a successful purchase.
            activePurchases().firstOrNull { it.productId == ref.productId }
                ?.let { StorePurchaseOutcome.Purchased(it) }
                ?: throw result.toException()
        }
        else -> throw result.toException()
    }

    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        // A failed acknowledge is retried on the next purchase query, well within Play's 3 days.
        withConnection {
            suspendCancellableCoroutine { cont -> client.acknowledgePurchase(params) { cont.resume(it) } }
        }
    }

    private suspend fun queryDetails(productIds: List<String>, type: String): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                productIds.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
                },
            )
            .build()
        val (result, details) = withConnection {
            suspendCancellableCoroutine { cont ->
                client.queryProductDetailsAsync(params) { result, response ->
                    cont.resume(result to response.productDetailsList)
                }
            }
        }
        if (result.responseCode != BillingResponseCode.OK) throw result.toException()
        return details
    }

    private suspend fun queryPurchases(type: String): List<Purchase> {
        val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
        val (result, purchases) = withConnection {
            suspendCancellableCoroutine { cont ->
                client.queryPurchasesAsync(params) { result, list -> cont.resume(result to list) }
            }
        }
        if (result.responseCode != BillingResponseCode.OK) throw result.toException()
        return purchases
    }

    /** Runs [block] on a connected client; a connection Play dropped is re-opened here. */
    private suspend fun <T> withConnection(block: suspend () -> T): T {
        ensureConnected()
        return block()
    }

    private suspend fun ensureConnected() = connectMutex.withLock {
        if (client.isReady) return@withLock
        val result = suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
        if (result.responseCode != BillingResponseCode.OK) throw result.toException()
    }

    private inner class ActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (startedActivities++ == 0) updates.tryEmit(Unit)
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
        }

        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            currentActivity = WeakReference(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity.get() === activity) currentActivity = WeakReference(null)
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

/** A configured product id: `subscriptionId[:basePlanId]`. */
private class ProductRef(val id: String) {
    val productId: String = id.substringBefore(':')
    val basePlanId: String? = id.substringAfter(':', "").ifEmpty { null }
}

/**
 * Picks the offer to sell for [basePlanId] (or the only base plan): an offer
 * with a free trial if Play returned one (Play only returns offers the user is
 * eligible for), otherwise the base plan itself.
 */
private fun ProductDetails.chooseOffer(basePlanId: String?): ProductDetails.SubscriptionOfferDetails? {
    val offers = subscriptionOfferDetails.orEmpty()
        .filter { basePlanId == null || it.basePlanId == basePlanId }
    return offers.firstOrNull { offer -> offer.offerId != null && offer.freePhase() != null }
        ?: offers.firstOrNull { it.offerId == null }
        ?: offers.firstOrNull()
}

private fun ProductDetails.SubscriptionOfferDetails.freePhase(): ProductDetails.PricingPhase? =
    pricingPhases.pricingPhaseList.firstOrNull { it.priceAmountMicros == 0L }

private fun ProductDetails.SubscriptionOfferDetails.fullPricePhase(): ProductDetails.PricingPhase? =
    pricingPhases.pricingPhaseList.lastOrNull()

private fun ProductDetails.toStoreProduct(ref: ProductRef): StoreProduct? {
    if (productType == ProductType.INAPP) {
        val offer = oneTimePurchaseOfferDetails ?: return null
        return StoreProduct(
            id = ref.id,
            price = Price(offer.priceAmountMicros, offer.priceCurrencyCode, offer.formattedPrice),
            period = null,
            freeTrial = null,
        )
    }
    val offer = chooseOffer(ref.basePlanId) ?: return null
    val full = offer.fullPricePhase() ?: return null
    val price = Price(full.priceAmountMicros, full.priceCurrencyCode, full.formattedPrice)
    val billingPeriod = parseIsoPeriod(full.billingPeriod)
    val trial = offer.freePhase()?.let { free ->
        parseIsoPeriod(free.billingPeriod)?.let { period ->
            FreeTrial(
                period = period.copy(value = period.value * free.billingCycleCount.coerceAtLeast(1)),
                priceAfterTrial = price,
                billingPeriod = billingPeriod,
            )
        }
    }
    return StoreProduct(id = ref.id, price = price, period = billingPeriod, freeTrial = trial)
}

private fun Purchase.toTransactions(): List<StoreTransaction> = products.map { productId ->
    StoreTransaction(
        productId = productId,
        transactionId = orderId ?: purchaseToken,
        purchaseTimeMillis = purchaseTime,
        expirationTimeMillis = null,
    )
}

/** Parses Play's ISO 8601 periods such as `P3D`, `P1W`, `P6M`, `P1Y`. */
internal fun parseIsoPeriod(iso: String): Period? {
    val match = Regex("""P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?""").matchEntire(iso) ?: return null
    val (years, months, weeks, days) = match.destructured
    return when {
        years.isNotEmpty() -> Period(years.toInt(), PeriodUnit.YEAR)
        months.isNotEmpty() -> Period(months.toInt(), PeriodUnit.MONTH)
        weeks.isNotEmpty() -> Period(weeks.toInt(), PeriodUnit.WEEK)
        days.isNotEmpty() -> Period(days.toInt(), PeriodUnit.DAY)
        else -> null
    }
}

private fun BillingResult.toException(): BillingException {
    val code = when (responseCode) {
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.BILLING_UNAVAILABLE,
        -> BillingErrorCode.STORE_UNAVAILABLE
        BillingResponseCode.NETWORK_ERROR -> BillingErrorCode.NETWORK_ERROR
        BillingResponseCode.ITEM_UNAVAILABLE -> BillingErrorCode.PRODUCT_NOT_AVAILABLE
        BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingErrorCode.NOT_ALLOWED
        else -> BillingErrorCode.UNKNOWN
    }
    val message = debugMessage.ifBlank { "Google Play error ($responseCode)" }
    return BillingException(code, message)
}
