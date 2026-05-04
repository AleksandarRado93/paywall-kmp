package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.LogLevel

/**
 * Configuration required to initialize RevenueCat and resolve premium entitlements.
 *
 * @property apiKey RevenueCat platform-specific API key. Production Android keys
 *   start with `goog_`, production iOS keys start with `appl_`. Cross-platform
 *   sandbox/test keys (`test_*`) target RevenueCat's Test Store and are only
 *   accepted when [acceptTestKeys] is true.
 * @property entitlementId The entitlement identifier configured in your RevenueCat
 *   dashboard (e.g. "premium", "pro"). This is what `CustomerInfo.entitlements[id]`
 *   keys against.
 * @property logLevel RevenueCat SDK log level. Pass [LogLevel.DEBUG] in debug builds,
 *   [LogLevel.WARN] (default) or [LogLevel.ERROR] in production.
 * @property acceptTestKeys Whether to accept `test_*` API keys. Defaults to `false`
 *   so that production-signed builds never accidentally initialize RevenueCat with
 *   a sandbox key (which would crash on launch). Pass `true` from your app when
 *   you know you're in a development build, e.g. `acceptTestKeys = isDebugBuild`.
 */
data class RevenueCatConfig(
    val apiKey: String,
    val entitlementId: String,
    val logLevel: LogLevel = LogLevel.WARN,
    val acceptTestKeys: Boolean = false,
)

/**
 * Validates [apiKey] for use with `Purchases.configure(...)`. Returns true when:
 *  - The key starts with `appl_` (production iOS) or `goog_` (production Android), OR
 *  - The key starts with `test_` AND [acceptTestKeys] is true.
 *
 * RevenueCat refuses to start in production-signed builds when given a sandbox
 * key, and it crashes with an unchecked exception if the key is blank. The
 * library uses this guard inside [configureRevenueCat] so the app launches
 * with subscriptions disabled instead of crashing on first frame.
 *
 * Apps should pass [RevenueCatConfig.acceptTestKeys]=true only in debug builds
 * (e.g. `acceptTestKeys = isDebugBuild`) so that release builds never accept
 * a sandbox key by mistake.
 */
fun isRevenueCatApiKeyValid(apiKey: String, acceptTestKeys: Boolean = false): Boolean {
    if (apiKey.isBlank()) return false
    if (apiKey.startsWith("appl_") || apiKey.startsWith("goog_")) return true
    return acceptTestKeys && apiKey.startsWith("test_")
}
