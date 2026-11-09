package io.github.aleksandarrado93.paywall.extensions

import io.github.aleksandarrado93.paywall.CustomerInfo

/**
 * True when this CustomerInfo grants premium. Nullable receiver so callers can
 * write `customerInfo.hasActivePremium()` without a redundant `?:`.
 */
fun CustomerInfo?.hasActivePremium(): Boolean = this?.isPremium == true

/**
 * Product id of the purchase granting premium, or null if none is active.
 * Useful for "you are subscribed to <plan>" UI.
 */
fun CustomerInfo?.activePremiumProductId(): String? = this?.activePremiumProductId
