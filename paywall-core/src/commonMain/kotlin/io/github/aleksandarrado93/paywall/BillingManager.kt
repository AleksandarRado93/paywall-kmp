package io.github.aleksandarrado93.paywall

import io.github.aleksandarrado93.paywall.bridges.NoOpPaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.store.StoreClient
import io.github.aleksandarrado93.paywall.store.StorePurchaseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine facade over a [StoreClient]. Owns the latest known [CustomerInfo]
 * as a [StateFlow] so consumers can react to premium changes without polling.
 *
 * Call [start] at app launch (Android `Application.onCreate`, iOS `iOSApp.init`)
 * so renewals, refunds and purchases finished while the app was closed are
 * picked up. Read methods catch store failures, report them through
 * [crashReporter] and return null; [purchase] and [restorePurchases] throw
 * [io.github.aleksandarrado93.paywall.store.BillingException].
 *
 * @param scope Where store updates are collected. Defaults to an app-lifetime scope.
 */
class BillingManager(
    private val config: BillingConfig,
    private val store: StoreClient,
    private val crashReporter: PaywallCrashReporterBridge = NoOpPaywallCrashReporterBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    /**
     * Optional id of your own user account, attached to new purchases so a
     * backend can link them later. Must be a UUID string for the App Store.
     */
    var appAccountToken: String? = null

    private val productsMutex = Mutex()
    private var cachedProducts: Map<String, StoreProduct>? = null
    private var started = false

    /** Starts the store listener and loads the current purchases. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        store.start()
        scope.launch {
            store.purchaseUpdates.collect { refreshCustomerInfo() }
        }
        scope.launch { refreshCustomerInfo() }
    }

    suspend fun refreshCustomerInfo(): CustomerInfo? {
        start()
        return try {
            customerInfoOf(store.activePurchases()).also { _customerInfo.value = it }
        } catch (e: Exception) {
            crashReporter.logNonFatal(e)
            null
        }
    }

    /** The first offering of [BillingConfig.offerings], or null when none of its products loaded. */
    suspend fun getCurrentOffering(): Offering? = getOffering(config.currentOffering.identifier)

    /**
     * The offering named [identifier] in [BillingConfig.offerings], independent
     * of which one is current. Null when it isn't configured, none of its
     * products loaded, or the store failed (reported through [crashReporter]).
     *
     * Useful for promotional / discounted / win-back paywalls.
     */
    suspend fun getOffering(identifier: String): Offering? {
        val offeringConfig = config.offerings.firstOrNull { it.identifier == identifier } ?: return null
        val products = products() ?: return null
        val packages = offeringConfig.products.mapNotNull { (type, productId) ->
            products[productId]?.let { Package(identifier = productId, packageType = type, storeProduct = it) }
        }
        return packages.takeIf { it.isNotEmpty() }?.let { Offering(identifier, it) }
    }

    /**
     * Opens the store purchase sheet for [packageToPurchase]. Throws
     * [io.github.aleksandarrado93.paywall.store.BillingException] on failure.
     */
    suspend fun purchase(packageToPurchase: Package): PurchaseResult {
        start()
        return when (val outcome = store.purchase(packageToPurchase.storeProduct, appAccountToken)) {
            is StorePurchaseOutcome.Purchased -> {
                val purchases = runCatching { store.activePurchases() }
                    .onFailure { crashReporter.logNonFatal(it) }
                    .getOrDefault(emptyList())
                    .let { active ->
                        if (active.any { it.transactionId == outcome.transaction.transactionId }) {
                            active
                        } else {
                            active + outcome.transaction
                        }
                    }
                val info = customerInfoOf(purchases)
                _customerInfo.value = info
                PurchaseResult.Success(outcome.transaction, info)
            }
            StorePurchaseOutcome.Pending -> PurchaseResult.Pending
            StorePurchaseOutcome.Cancelled -> PurchaseResult.Cancelled
        }
    }

    suspend fun restorePurchases(): CustomerInfo {
        start()
        val info = customerInfoOf(store.restore())
        _customerInfo.value = info
        return info
    }

    private suspend fun products(): Map<String, StoreProduct>? = productsMutex.withLock {
        cachedProducts?.let { return it }
        try {
            store.loadProducts(config.allProductIds)
                .associateBy { it.id }
                // An empty result is usually a store hiccup; retry on the next call.
                .also { if (it.isNotEmpty()) cachedProducts = it }
        } catch (e: Exception) {
            crashReporter.logNonFatal(e)
            null
        }
    }

    private fun customerInfoOf(purchases: List<StoreTransaction>) = CustomerInfo(
        activePurchases = purchases,
        activePremiumProductId = purchases.firstOrNull { config.grantsPremium(it.productId) }?.productId,
    )
}
