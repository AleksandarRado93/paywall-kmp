package io.github.aleksandarrado93.paywall.bridges

/**
 * Bridge for reporting non-fatal exceptions to the consumer app's crash reporting
 * SDK (Firebase Crashlytics, Sentry, Bugsnag, etc.). The library uses this to
 * surface RevenueCat init failures, customer-info refresh errors, and other
 * recoverable failures that should still be visible in dashboards.
 *
 * Implementations must not throw — failures inside [logNonFatal] should be
 * swallowed silently. Pass [NoOpPaywallCrashReporterBridge] if you do not yet
 * have crash reporting wired up.
 */
interface PaywallCrashReporterBridge {
    fun logNonFatal(throwable: Throwable)
}

/**
 * Default no-op implementation. Useful during early bring-up before the
 * consumer app has wired Crashlytics or similar.
 */
object NoOpPaywallCrashReporterBridge : PaywallCrashReporterBridge {
    override fun logNonFatal(throwable: Throwable) {}
}
