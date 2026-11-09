package io.github.aleksandarrado93.paywall

/**
 * Which products the app sells and which of them grant premium.
 *
 * Product ids are platform specific, so build this per platform:
 * - **Google Play:** `subscriptionId:basePlanId` (e.g. `premium:monthly`), or a
 *   plain `subscriptionId` when the subscription has one base plan. One-time
 *   products ([PackageType.LIFETIME]) use the plain product id.
 * - **App Store:** the product id (e.g. `com.example.premium.monthly`).
 *
 * @property offerings The paywalls the app can show. The first one is the
 *   "current" offering returned by [BillingManager.getCurrentOffering].
 * @property premiumProductIds Store product ids that grant premium (on Android
 *   the subscription id without the base plan). Null means every purchase of
 *   this app grants premium.
 */
data class BillingConfig(
    val offerings: List<OfferingConfig>,
    val premiumProductIds: Set<String>? = null,
) {
    init {
        require(offerings.isNotEmpty()) { "BillingConfig needs at least one offering" }
        require(offerings.map { it.identifier }.toSet().size == offerings.size) {
            "Offering identifiers must be unique"
        }
    }

    val currentOffering: OfferingConfig get() = offerings.first()

    /** Every product id listed in any offering. */
    val allProductIds: List<String> get() = offerings.flatMap { it.products.values }.distinct()

    /** True when a purchase of [productId] grants premium. */
    fun grantsPremium(productId: String): Boolean =
        premiumProductIds == null || productId.substringBefore(':') in premiumProductIds
}

/**
 * One paywall's plans.
 *
 * @property identifier Name used with [BillingManager.getOffering], e.g. "gift_offer".
 * @property products Product id for each plan on this paywall.
 */
data class OfferingConfig(
    val identifier: String,
    val products: Map<PackageType, String>,
)
