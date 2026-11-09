package io.github.aleksandarrado93.paywall.ios

import io.github.aleksandarrado93.paywall.StoreProduct
import io.github.aleksandarrado93.paywall.StoreTransaction

/**
 * StoreKit 2 is Swift-only, so Kotlin reaches it through this interface. The
 * implementation ships as `ios/PaywallStoreKitBridge.swift` in this repository:
 * copy it into the iOS app and pass an instance to [IosStoreClient].
 *
 * Callbacks may be invoked on any thread, exactly once per call (except
 * `onTransactionsChanged`, which fires on every change).
 */
interface StoreKitBridge {

    /**
     * Starts listening to `Transaction.updates` and finishes unfinished
     * transactions. [onTransactionsChanged] fires whenever purchases may have changed.
     */
    fun start(onTransactionsChanged: () -> Unit)

    /**
     * Loads [productIds]. [onResult] gets the products the App Store knows, each
     * with a free trial only when this user is eligible, or an error message.
     */
    fun loadProducts(productIds: List<String>, onResult: (products: List<StoreProduct>, error: String?) -> Unit)

    /** Buys [productId]; [appAccountToken] is a UUID string or null. */
    fun purchase(productId: String, appAccountToken: String?, onResult: (StoreKitPurchaseResult) -> Unit)

    /** Reads `Transaction.currentEntitlements` (verified, not revoked). */
    fun activePurchases(onResult: (purchases: List<StoreTransaction>, error: String?) -> Unit)

    /** Runs `AppStore.sync()`; [onResult] gets an error message or null. */
    fun restore(onResult: (error: String?) -> Unit)
}

enum class StoreKitPurchaseStatus { PURCHASED, PENDING, CANCELLED, FAILED, VERIFICATION_FAILED }

/**
 * Result of [StoreKitBridge.purchase].
 *
 * @property transaction Set when [status] is [StoreKitPurchaseStatus.PURCHASED].
 * @property errorMessage Set when the purchase failed.
 */
data class StoreKitPurchaseResult(
    val status: StoreKitPurchaseStatus,
    val transaction: StoreTransaction?,
    val errorMessage: String?,
)
