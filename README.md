# paywall-kmp

A reusable RevenueCat-backed subscription/billing layer for Kotlin Multiplatform apps (Android + iOS).

Extracted from a production app to provide a single source of truth for the parts of paywall code that don't change between apps: `BillingManager`, `PaywallViewModel`, the purchase state machine, and the helpers that prevent common production crashes (e.g. sandbox keys in production builds).

The UI stays per-app — bring your own Compose Multiplatform `PaywallScreen` and call into the library's ViewModel.

## What's in the box

| Class | Purpose |
|---|---|
| `RevenueCatConfig` | Holds API key, entitlement ID, SDK log level, and the `acceptTestKeys` flag |
| `isRevenueCatApiKeyValid(apiKey, acceptTestKeys)` | Validates the key — accepts `appl_*`/`goog_*` always, accepts `test_*` only when `acceptTestKeys=true` |
| `configureRevenueCat(config, crashReporter?)` | Crash-safe RC initialization, returns `Boolean` for success |
| `BillingManager` | Coroutine wrapper around RevenueCat with `customerInfo: StateFlow` |
| `PaywallViewModel` | Plans/purchase state machine with timeout handling, restore, error mapping |
| `CustomerInfo.hasActivePremium(entitlementId)` | Extension for entitlement checks |
| `PremiumStateRepository` | Bridge — your DataStore/SharedPreferences cache |
| `PaywallAnalyticsBridge` | Bridge — your Firebase/Amplitude/etc. analytics provider |
| `PaywallCrashReporterBridge` | Bridge — your Crashlytics/Sentry/etc. crash reporter |

## Installation

### 1. Add the GitHub Packages repository

In your consumer project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/AleksandarRado93/paywall-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

### 2. Add credentials in `~/.gradle/gradle.properties`

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN_WITH_read:packages
```

### 3. Add the dependency

In your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aleksandarrado93.paywall:paywall-core:0.2.1")
        }
    }
}
```

### 4. Link `PurchasesHybridCommon` via SPM (iOS only)

The library transitively depends on the RevenueCat KMP SDK, but iOS still requires the native `PurchasesHybridCommon` Swift package linked via Xcode → Package Dependencies. Without this, iOS will fail at link time with `Undefined symbols: _OBJC_CLASS_$_RCPurchases`.

```
File → Add Package Dependencies →
  https://github.com/RevenueCat/purchases-hybrid-common
  → Up to Next Major Version: 17.x
  → Add PurchasesHybridCommon to your iOS target
```

## Quick start

```kotlin
// 1. Define your config
val rcConfig = RevenueCatConfig(
    apiKey = if (isAndroid) "goog_..." else "appl_...",
    entitlementId = "premium",
    logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
    // Accept RC's cross-platform `test_*` sandbox keys ONLY in debug builds.
    // Release builds will reject them so a sandbox key can never accidentally
    // ship to production (which would crash on launch).
    acceptTestKeys = BuildConfig.DEBUG,
)

// 2. Initialize RC at app startup (Android: Application.onCreate; iOS: iOSApp.swift)
configureRevenueCat(rcConfig, crashReporter = MyCrashReporterBridge)

// 3. Implement the three bridges (one-time per app)
class MyPremiumStateRepository(private val dataStore: DataStore<Preferences>) : PremiumStateRepository {
    override val isPremium: Flow<Boolean> = dataStore.data.map { it[booleanPreferencesKey("is_premium")] ?: false }
    override suspend fun setPremium(isPremium: Boolean) { dataStore.edit { it[booleanPreferencesKey("is_premium")] = isPremium } }
}

object MyAnalyticsBridge : PaywallAnalyticsBridge {
    override fun subscriptionConverted(plan: String) { Firebase.analytics.logEvent("subscription_convert") { param("plan", plan) } }
    override fun purchaseFailed(errorType: String) { Firebase.analytics.logEvent("paywall_purchase_failed") { param("error_type", errorType) } }
}

object MyCrashReporterBridge : PaywallCrashReporterBridge {
    override fun logNonFatal(throwable: Throwable) { Firebase.crashlytics.recordException(throwable) }
}

// 4. Wire the ViewModel via Koin (or your DI of choice)
val paywallModule = module {
    single { rcConfig }
    single { BillingManager(crashReporter = MyCrashReporterBridge) }
    single<PremiumStateRepository> { MyPremiumStateRepository(get()) }
    single<PaywallAnalyticsBridge> { MyAnalyticsBridge }
    single<PaywallCrashReporterBridge> { MyCrashReporterBridge }
    viewModel { PaywallViewModel(get(), get(), get(), get(), get()) }
}

// 5. Use it from your Compose UI
@Composable
fun PaywallScreen(viewModel: PaywallViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.purchaseSuccess.collect { /* navigate away */ }
    }

    when (val plans = state.plans) {
        PlansState.Loading -> CircularProgressIndicator()
        PlansState.Unavailable -> Text("Subscriptions unavailable. Try again later.")
        is PlansState.Ready -> {
            Button(onClick = { viewModel.purchase(PackageType.WEEKLY, planLabel = "Weekly") }) {
                Text("Subscribe weekly")
            }
            Button(onClick = { viewModel.purchase(PackageType.ANNUAL, planLabel = "Yearly") }) {
                Text("Subscribe yearly")
            }
            TextButton(onClick = { viewModel.restorePurchases() }) {
                Text("Restore purchases")
            }
        }
    }
}
```

## Why bridges instead of direct Firebase dependency?

The library is intentionally agnostic about your analytics/crash/persistence stack. You might use Firebase, you might use Amplitude or Sentry. By keeping these as bridges:

- The library has zero Firebase/Crashlytics/DataStore dependencies
- You can swap analytics providers without touching the library
- You can ship a "no-op" implementation in test builds via `NoOpPaywallAnalyticsBridge` and `NoOpPaywallCrashReporterBridge`

## Common pitfalls

A few non-obvious gotchas the library can't fix for you:

### iOS Swift symbols and the `.ios.kt` filename suffix

If you call into Kotlin from Swift via the `*Kt` file accessor (e.g. `MyHelperKt.something()`), the file containing the `actual` function in `iosMain` should be named `MyHelper.kt` — **not** `MyHelper.ios.kt`. Kotlin/Native turns `MyHelper.ios.kt` into the Swift symbol `MyHelper_iosKt`, and your existing Swift call won't compile.

Either drop the `.ios.kt` suffix on files Swift directly calls into, or update the Swift caller to use `MyHelper_iosKt.something()`.

### Koin `viewModelOf(::PaywallViewModel)` requires explicit DSL

`PaywallViewModel` has a `productLoadTimeoutMs: Long = 5_000L` parameter with a default value. Koin's `viewModelOf(::PaywallViewModel)` shortcut is reflection-based and doesn't honor Kotlin default values — it will crash at first paywall open with `NoDefinitionFoundException: java.lang.Long`. Use the explicit DSL instead:

```kotlin
viewModel {
    PaywallViewModel(
        config = get(),
        billingManager = get(),
        premiumRepository = get(),
        analytics = get(),
        crashReporter = get(),
    )
}
```

### `test_*` API keys in production builds

RevenueCat's cross-platform `test_*` keys point at the Test Store and crash production-signed apps on launch. Always set `acceptTestKeys = isDebugBuild` in your `RevenueCatConfig` so release builds reject them automatically.

## Versioning

Pre-1.0 (`0.x.y`) versions may have breaking API changes between minor versions. Once `1.0.0` ships, semantic versioning applies strictly.

## Changelog

### 0.2.1
- Lowered Android `minSdk` from 29 to 21 (Android 5.0 Lollipop, matching RevenueCat's own minimum). Prevents the library from forcing consumers onto Android 10+.

### 0.2.0
- Added `RevenueCatConfig.acceptTestKeys` flag — set to `true` (typically `isDebugBuild`) to allow cross-platform `test_*` API keys for prototyping against RevenueCat's Test Store. Default `false` preserves production safety.
- Documented the `.ios.kt` filename → Swift `*_iosKt` symbol gotcha.
- Documented the Koin `viewModelOf` + default param value gotcha.

### 0.1.0
- Initial release.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
