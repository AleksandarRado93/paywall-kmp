package io.github.aleksandarrado93.paywall

/** Unit of a subscription or trial [Period]. */
enum class PeriodUnit { DAY, WEEK, MONTH, YEAR }

/** A length of time as the store reports it, e.g. 3 days or 6 months. */
data class Period(val value: Int, val unit: PeriodUnit)

/**
 * A price in the user's store currency.
 *
 * @property amountMicros The price × 1,000,000 (e.g. 4.99 → 4_990_000).
 * @property currencyCode ISO 4217 code, e.g. "USD".
 * @property formatted The store's localized price text, e.g. "$4.99".
 */
data class Price(
    val amountMicros: Long,
    val currencyCode: String,
    val formatted: String,
)

/**
 * A product loaded from Google Play or the App Store.
 *
 * @property id The product id as written in [BillingConfig] (on Android this may
 *   include a base plan: `subscriptionId:basePlanId`).
 * @property price The regular, recurring price.
 * @property period How often [price] renews, or null for a one-time purchase.
 * @property freeTrial The free trial this user can still get, or null. Both
 *   stores are already eligibility-filtered here, so show trial copy whenever
 *   this is set.
 */
data class StoreProduct(
    val id: String,
    val price: Price,
    val period: Period?,
    val freeTrial: FreeTrial?,
)

/**
 * A free trial on a subscription.
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

/** The plan length a [Package] stands for on the paywall. */
enum class PackageType { WEEKLY, MONTHLY, TWO_MONTH, THREE_MONTH, SIX_MONTH, ANNUAL, LIFETIME }

/**
 * One purchasable plan of an [Offering].
 *
 * @property identifier Unique id of the package, equal to the product id.
 */
data class Package(
    val identifier: String,
    val packageType: PackageType,
    val storeProduct: StoreProduct,
)

/**
 * A set of plans shown together on a paywall, built from an [OfferingConfig].
 * Only packages whose product the store returned are included.
 */
data class Offering(
    val identifier: String,
    val availablePackages: List<Package>,
) {
    val weekly: Package? get() = packageOf(PackageType.WEEKLY)
    val monthly: Package? get() = packageOf(PackageType.MONTHLY)
    val twoMonth: Package? get() = packageOf(PackageType.TWO_MONTH)
    val threeMonth: Package? get() = packageOf(PackageType.THREE_MONTH)
    val sixMonth: Package? get() = packageOf(PackageType.SIX_MONTH)
    val annual: Package? get() = packageOf(PackageType.ANNUAL)
    val lifetime: Package? get() = packageOf(PackageType.LIFETIME)
}

/**
 * Looks up a package by [PackageType], or null when the offering doesn't include
 * that type. Useful in Compose UI for reading the formatted price of a plan.
 */
fun Offering.packageOf(type: PackageType): Package? =
    availablePackages.firstOrNull { it.packageType == type }

/**
 * A purchase the store currently grants: an active subscription (including one
 * in a billing grace period) or a one-time purchase that wasn't refunded.
 *
 * @property productId Store product id. On Android this is the subscription id
 *   without the base plan.
 * @property transactionId Play order id / purchase token, or the App Store
 *   transaction id.
 * @property purchaseTimeMillis When it was bought (epoch millis).
 * @property expirationTimeMillis When it expires, or null if unknown (Play does
 *   not report it on device) or it never expires.
 */
data class StoreTransaction(
    val productId: String,
    val transactionId: String,
    val purchaseTimeMillis: Long,
    val expirationTimeMillis: Long?,
)

/**
 * What the store says this user owns right now, as resolved by [BillingManager].
 *
 * @property activePurchases Everything currently granted by the store.
 * @property activePremiumProductId Product id of the purchase that grants
 *   premium (see [BillingConfig.premiumProductIds]), or null when not premium.
 */
data class CustomerInfo(
    val activePurchases: List<StoreTransaction>,
    val activePremiumProductId: String?,
) {
    val isPremium: Boolean get() = activePremiumProductId != null
}
