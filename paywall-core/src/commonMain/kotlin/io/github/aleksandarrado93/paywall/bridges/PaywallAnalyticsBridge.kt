package io.github.aleksandarrado93.paywall.bridges

/**
 * Bridge for sending paywall analytics events to the consumer app's analytics
 * pipeline (Firebase Analytics, Amplitude, Mixpanel, etc.). All methods are
 * fire-and-forget — implementations must not throw.
 *
 * Calls are made from the library's [io.github.aleksandarrado93.paywall.PaywallViewModel]
 * during the purchase flow. Pass [NoOpPaywallAnalyticsBridge] if you do not yet
 * have analytics wired up.
 */
interface PaywallAnalyticsBridge {
    /**
     * A subscription was successfully activated. [plan] is a free-form label
     * (typically the variant name like "Weekly" / "Yearly" / "Trial") for
     * conversion attribution.
     */
    fun subscriptionConverted(plan: String)

    /**
     * A purchase attempt failed for a non-cancellation reason. [errorType] is
     * a short machine-readable token (RevenueCat error code or exception class name).
     */
    fun purchaseFailed(errorType: String)
}

/**
 * Default no-op implementation. Useful during early bring-up before the
 * consumer app has wired its analytics provider.
 */
object NoOpPaywallAnalyticsBridge : PaywallAnalyticsBridge {
    override fun subscriptionConverted(plan: String) {}
    override fun purchaseFailed(errorType: String) {}
}
