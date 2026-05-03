package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.StoreTransaction

/**
 * Result of a successful [BillingManager.purchase] call. Contains the underlying
 * store transaction (null if RevenueCat resolved it without a fresh transaction,
 * e.g. a re-issued entitlement) and the latest [CustomerInfo] reflecting the new
 * entitlement state.
 */
data class PurchaseResult(
    val transaction: StoreTransaction?,
    val customerInfo: CustomerInfo,
)
