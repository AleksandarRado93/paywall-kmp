package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge

/**
 * Initializes RevenueCat with the given [config]. Safe to call on app startup —
 * will not throw and will not crash if the API key is invalid.
 *
 * On Android, call this from your [android.app.Application.onCreate]. On iOS,
 * call this from `iOSApp.swift` via the generated `RevenueCatInitKt` accessor.
 *
 * @param crashReporter Optional bridge used to report initialization failures as
 *   non-fatal events. Pass null in early bring-up if you have not wired Crashlytics yet.
 *
 * @return true if RevenueCat was initialized, false if it was skipped (invalid key)
 *   or failed to initialize. When false, the app should treat all subscriptions as
 *   inactive and the paywall will gracefully render an "unavailable" state.
 */
fun configureRevenueCat(
    config: RevenueCatConfig,
    crashReporter: PaywallCrashReporterBridge? = null,
): Boolean {
    if (!isRevenueCatApiKeyValid(config.apiKey)) return false
    Purchases.logLevel = config.logLevel
    return try {
        Purchases.configure(apiKey = config.apiKey)
        true
    } catch (e: Throwable) {
        crashReporter?.logNonFatal(Exception("RevenueCat configure failed", e))
        false
    }
}
