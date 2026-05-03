# paywall-kmp

A reusable RevenueCat-backed subscription/billing layer for Kotlin Multiplatform apps (Android + iOS).

Extracted from a production app to provide a single source of truth for the parts of paywall code that don't change between apps: `BillingManager`, `PaywallViewModel`, the purchase state machine, and the helpers that prevent common production crashes (e.g. sandbox keys in production builds).

The UI stays per-app — bring your own Compose Multiplatform `PaywallScreen` and call into the library's ViewModel.

## What's in the box

| Class | Purpose |
|---|---|
| `RevenueCatConfig` | Holds API key, entitlement ID, and SDK log level |
| `isRevenueCatApiKeyValid(apiKey)` | Guard against `test_*` sandbox keys in production |
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
            implementation("io.github.aleksandarrado93.paywall:paywall-core:0.1.0")
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

## Versioning

Pre-1.0 (`0.x.y`) versions may have breaking API changes between minor versions. Once `1.0.0` ships, semantic versioning applies strictly.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
