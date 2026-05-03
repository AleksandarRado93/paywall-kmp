package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.LogLevel

/**
 * Configuration required to initialize RevenueCat and resolve premium entitlements.
 *
 * @property apiKey RevenueCat platform-specific API key. Android keys start with `goog_`,
 *   iOS keys start with `appl_`. Sandbox/test keys (`test_*`) are rejected by
 *   [isRevenueCatApiKeyValid] to prevent production crashes.
 * @property entitlementId The entitlement identifier configured in your RevenueCat
 *   dashboard (e.g. "premium", "pro"). This is what `CustomerInfo.entitlements[id]`
 *   keys against.
 * @property logLevel RevenueCat SDK log level. Pass [LogLevel.DEBUG] in debug builds,
 *   [LogLevel.WARN] (default) or [LogLevel.ERROR] in production.
 */
data class RevenueCatConfig(
    val apiKey: String,
    val entitlementId: String,
    val logLevel: LogLevel = LogLevel.WARN,
)

/**
 * RevenueCat refuses to start in production-signed builds when given a sandbox
 * key (`test_*` prefix), and it crashes with an unchecked exception if the key
 * is blank. Use this to guard `configureRevenueCat(...)` so the app launches
 * with subscriptions disabled instead of crashing on first frame.
 */
fun isRevenueCatApiKeyValid(apiKey: String): Boolean {
    if (apiKey.isBlank()) return false
    return apiKey.startsWith("appl_") || apiKey.startsWith("goog_")
}
