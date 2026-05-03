package io.github.aleksandarrado93.paywall.extensions

import com.revenuecat.purchases.kmp.models.CustomerInfo

/**
 * True when this CustomerInfo grants the given entitlement. Nullable receiver so
 * callers can write `customerInfo.hasActivePremium(id)` without a redundant `?:`.
 */
fun CustomerInfo?.hasActivePremium(entitlementId: String): Boolean =
    this?.entitlements?.get(entitlementId)?.isActive == true

/**
 * Product id of the currently active entitlement, or null if none is active.
 * Useful for "you are subscribed to <plan>" UI.
 */
fun CustomerInfo?.activePremiumProductId(entitlementId: String): String? =
    this?.entitlements?.get(entitlementId)
        ?.takeIf { it.isActive }
        ?.productIdentifier
