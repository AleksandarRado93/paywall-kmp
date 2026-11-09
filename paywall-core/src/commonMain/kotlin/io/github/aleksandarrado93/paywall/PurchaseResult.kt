package io.github.aleksandarrado93.paywall

/** How [BillingManager.purchase] ended, when it didn't throw. */
sealed interface PurchaseResult {
    /**
     * The store completed the purchase. [customerInfo] is re-read afterwards,
     * so check [CustomerInfo.isPremium] rather than assuming it.
     */
    data class Success(
        val transaction: StoreTransaction,
        val customerInfo: CustomerInfo,
    ) : PurchaseResult

    /** Payment needs more steps; premium unlocks later through [BillingManager.customerInfo]. */
    data object Pending : PurchaseResult

    data object Cancelled : PurchaseResult
}
