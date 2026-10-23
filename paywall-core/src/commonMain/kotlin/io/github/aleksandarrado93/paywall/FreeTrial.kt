package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.models.DiscountPaymentMode
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.Period
import com.revenuecat.purchases.kmp.models.Price
import com.revenuecat.purchases.kmp.models.freePhase
import com.revenuecat.purchases.kmp.models.fullPricePhase

/**
 * A free trial on a subscription package, as configured in the store.
 *
 * @property period How long the trial lasts (e.g. 3 days).
 * @property priceAfterTrial What the user is charged once the trial ends.
 * @property billingPeriod How often [priceAfterTrial] renews, or null if the
 *   store didn't report it.
 */
data class FreeTrial(
    val period: Period,
    val priceAfterTrial: Price,
    val billingPeriod: Period?,
)

/**
 * The free trial the store attaches to this package, or null when it has none.
 *
 * - **Play Store:** read from the default subscription option's free phase.
 *   Google only returns offers the user is eligible for, so this is already
 *   eligibility-filtered.
 * - **App Store:** read from the introductory discount when its payment mode is
 *   a free trial. This is **not** eligibility-filtered — a user who already
 *   used the trial still sees it here. Use
 *   [BillingManager.getEligibleFreeTrials] before showing trial copy.
 */
fun Package.freeTrial(): FreeTrial? {
    val product = storeProduct

    product.defaultOption?.let { option ->
        val free = option.freePhase ?: return null
        val full = option.fullPricePhase
        return FreeTrial(
            period = free.billingPeriod,
            priceAfterTrial = full?.price ?: product.price,
            billingPeriod = full?.billingPeriod ?: product.period,
        )
    }

    val intro = product.introductoryDiscount ?: return null
    if (intro.paymentMode != DiscountPaymentMode.FREE_TRIAL) return null
    val periods = intro.numberOfPeriods.toInt().coerceAtLeast(1)
    return FreeTrial(
        period = Period(intro.subscriptionPeriod.value * periods, intro.subscriptionPeriod.unit),
        priceAfterTrial = product.price,
        billingPeriod = product.period,
    )
}

/** App Store trials need a per-user eligibility check; Play Store trials don't. */
internal val Package.needsTrialEligibilityCheck: Boolean
    get() = storeProduct.defaultOption == null && storeProduct.introductoryDiscount != null
