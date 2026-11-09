package io.github.aleksandarrado93.paywall.store

import io.github.aleksandarrado93.paywall.StoreProduct
import io.github.aleksandarrado93.paywall.StoreTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Talks to one store. The library ships `AndroidStoreClient` (Google Play
 * Billing), `IosStoreClient` (StoreKit 2 through a Swift bridge) and
 * [FakeStoreClient] (tests and builds without store accounts).
 *
 * Failures other than a cancelled or pending purchase throw [BillingException].
 */
interface StoreClient {

    /**
     * Emits when purchases may have changed outside of [purchase]: renewals,
     * refunds, approved pending payments, Ask to Buy, or the app coming back to
     * the foreground. [io.github.aleksandarrado93.paywall.BillingManager]
     * re-reads [activePurchases] on every emission.
     */
    val purchaseUpdates: Flow<Unit>

    /**
     * Starts listening for store transactions. Must run at app launch so
     * renewals and purchases finished while the app was closed are handled.
     * Safe to call more than once.
     */
    fun start()

    /** Loads [productIds]; ids the store doesn't know are left out. */
    suspend fun loadProducts(productIds: List<String>): List<StoreProduct>

    /**
     * Opens the store's purchase sheet for [product].
     *
     * @param appAccountToken Optional id linking the purchase to your own user
     *   account (Play `obfuscatedAccountId`; App Store `appAccountToken`, which
     *   must be a UUID).
     */
    suspend fun purchase(product: StoreProduct, appAccountToken: String?): StorePurchaseOutcome

    /** Everything the store currently grants this user. */
    suspend fun activePurchases(): List<StoreTransaction>

    /**
     * Asks the store to sync purchases (App Store sign-in prompt on iOS) and
     * returns the active purchases afterwards.
     */
    suspend fun restore(): List<StoreTransaction>
}

/** How a purchase sheet ended, when it didn't fail. */
sealed interface StorePurchaseOutcome {
    data class Purchased(val transaction: StoreTransaction) : StorePurchaseOutcome

    /** Payment needs more steps (cash payment, Ask to Buy). Premium unlocks later via [StoreClient.purchaseUpdates]. */
    data object Pending : StorePurchaseOutcome

    data object Cancelled : StorePurchaseOutcome
}

/** Machine-readable reason of a [BillingException], used for analytics. */
enum class BillingErrorCode {
    STORE_UNAVAILABLE,
    NETWORK_ERROR,
    PRODUCT_NOT_AVAILABLE,
    PAYMENT_DECLINED,
    NOT_ALLOWED,
    VERIFICATION_FAILED,
    UNKNOWN,
}

class BillingException(
    val code: BillingErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
