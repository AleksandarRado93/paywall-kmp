# paywall-kmp

A reusable subscription/billing layer for Kotlin Multiplatform apps (Android + iOS), built directly on **Google Play Billing** and **StoreKit 2** — no third-party billing service.

Extracted from a production app to provide a single source of truth for the parts of paywall code that don't change between apps: `BillingManager`, `PaywallViewModel`, the purchase state machine, and the store edge cases that cause lost revenue in hand-written billing code (unacknowledged Play purchases, missed renewals, pending payments).

The UI stays per-app — bring your own Compose Multiplatform `PaywallScreen` and call into the library's ViewModel.

> **Upgrading from 0.x (RevenueCat)?** See [Migrating from 0.5.x](#migrating-from-05x). 0.5.0 stays published, so you can pin it again at any time.

## What's in the box

| Class | Purpose |
|---|---|
| `BillingConfig` / `OfferingConfig` | Which product ids the app sells, grouped into paywalls (offerings), and which grant premium |
| `BillingManager` | Coroutine facade over the store with `customerInfo: StateFlow` |
| `PaywallViewModel` | Plans/purchase state machine with timeout handling, pending payments, restore, error mapping |
| `StoreClient` | The store interface: `AndroidStoreClient` (Play Billing 8), `IosStoreClient` (StoreKit 2 via a Swift bridge), `FakeStoreClient` |
| `Offering`, `Package`, `PackageType`, `StoreProduct`, `Price`, `Period`, `FreeTrial` | Store-independent models |
| `CustomerInfo` | Active purchases + the product granting premium |
| `PremiumStateRepository` | Bridge — your DataStore/SharedPreferences cache |
| `PaywallAnalyticsBridge` | Bridge — your Firebase/Amplitude/etc. analytics provider |
| `PaywallCrashReporterBridge` | Bridge — your Crashlytics/Sentry/etc. crash reporter |

## What the store clients handle for you

**Android (`AndroidStoreClient`)**
- Acknowledges every purchase — Play refunds unacknowledged purchases after 3 days — including ones found later (completed pending payments, purchases made while the app was killed).
- Pending purchases (cash / slow payment methods) don't grant premium until Play reports them purchased.
- Re-checks purchases each time the app returns to the foreground.
- Picks the free-trial offer when Play returns one (Play only returns offers the user is eligible for), otherwise the base plan.
- Replaces the active subscription when the user switches plans.

**iOS (`IosStoreClient` + `PaywallStoreKitBridge.swift`)**
- Listens to `Transaction.updates` from launch (renewals, refunds, Ask to Buy) and finishes unfinished transactions.
- Premium comes from `Transaction.currentEntitlements`, verified on device by StoreKit — no server needed.
- Free trials are shown only when `isEligibleForIntroOffer` is true.
- Restore runs `AppStore.sync()`.

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

### 3. Add the dependency and export it to the iOS framework

In your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            // Gives the Kotlin types plain Swift names (StoreProduct, not Paywall_coreStoreProduct),
            // which PaywallStoreKitBridge.swift relies on.
            export("io.github.aleksandarrado93.paywall:paywall-core:1.0.0-alpha01")
        }
    }
    sourceSets {
        commonMain.dependencies {
            api("io.github.aleksandarrado93.paywall:paywall-core:1.0.0-alpha01")
        }
    }
}
```

Requires Kotlin 2.3.20+. Android pulls in Google Play Billing 8.0.0.

### 4. iOS: add the Swift bridge

StoreKit 2 is Swift-only, so Kotlin calls it through the `StoreKitBridge` interface. Copy [`ios/PaywallStoreKitBridge.swift`](ios/PaywallStoreKitBridge.swift) into your iOS app (add it to every app target) and change its `import ComposeApp` line if your framework has another name. Requires iOS 15+.

## Quick start

```kotlin
// 1. Describe what you sell. Product ids are per store:
//    Play: "subscriptionId:basePlanId" (or a plain id for one base plan / one-time products)
//    App Store: the product id.
val billingConfig = BillingConfig(
    offerings = listOf(
        OfferingConfig(
            identifier = "default", // the first offering is the "current" one
            products = mapOf(
                PackageType.MONTHLY to "premium:monthly",
                PackageType.ANNUAL to "premium:annual",
            ),
        ),
        OfferingConfig("gift_offer", mapOf(PackageType.SIX_MONTH to "premium:gift")),
    ),
    premiumProductIds = null, // null = every purchase of this app grants premium
)

// 2. Create the store client per platform
//    Android (Application.onCreate):  AndroidStoreClient(application)
//    iOS (iOSApp.init, Swift):        IosStoreClient(bridge: PaywallStoreKitBridge())
//    Tests / no store account yet:    FakeStoreClient(products = ...)

// 3. Start listening at launch, so renewals and purchases finished while the app was closed are seen
billingManager.start()

// 4. Implement the three bridges (one-time per app)
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

// 5. Wire it via Koin (or your DI of choice)
val paywallModule = module {
    single { billingConfig }
    single { BillingManager(config = get(), store = get(), crashReporter = MyCrashReporterBridge) }
    single<PremiumStateRepository> { MyPremiumStateRepository(get()) }
    single<PaywallAnalyticsBridge> { MyAnalyticsBridge }
    single<PaywallCrashReporterBridge> { MyCrashReporterBridge }
    viewModel { PaywallViewModel(get(), get(), get(), get()) }
}

// 6. Use it from your Compose UI
@Composable
fun PaywallScreen(viewModel: PaywallViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.purchaseSuccess.collect { /* navigate away */ }
    }

    if (state.purchase == PurchaseUiState.Pending) {
        Text("Payment pending. Premium unlocks as soon as it completes.")
    }

    when (val plans = state.plans) {
        PlansState.Loading -> CircularProgressIndicator()
        PlansState.Unavailable -> Text("Subscriptions unavailable. Try again later.")
        is PlansState.Ready -> {
            Button(onClick = { viewModel.purchase(PackageType.MONTHLY, planLabel = "Monthly") }) {
                Text("Subscribe monthly · ${plans.offering.monthly?.storeProduct?.price?.formatted}")
            }
            TextButton(onClick = { viewModel.restorePurchases() }) {
                Text("Restore purchases")
            }
        }
    }
}
```

## Free trials

Never hardcode trial copy. A trial is only available once per user, and the
store setup can change. `PaywallUiState.freeTrials` holds the trials this user
can actually get, keyed by package identifier, and is filled before
`PlansState.Ready` is published:

```kotlin
val trial = state.freeTrials[pkg.identifier]
if (trial != null) {
    // e.g. "3-day free trial, then $39.99 every 6 months"
    val length = "${trial.period.value}-${trial.period.unit.name.lowercase()}"
    Text("$length free trial, then ${trial.priceAfterTrial.formatted}")
}
```

`StoreProduct.freeTrial` (and `Package.freeTrial()`) is already eligibility-filtered on both stores, so it's safe to use outside `PaywallViewModel` too.

## Testing

| Where | How |
|---|---|
| Unit tests, debug builds | `FakeStoreClient`: set `nextPurchase` to `Succeed`, `Cancel`, `Pend` or `Fail(...)`; change `owned` and call `emitUpdate()` to simulate renewals, refunds and approved payments. |
| iOS, no App Store account | A StoreKit Configuration file (`.storekit`) selected in the Xcode scheme: local products, accelerated renewals, failed payments, refunds, Ask to Buy. |
| iOS, real store | App Store Connect sandbox testers and TestFlight. |
| Android | Upload a build to a Play testing track (internal is enough) and add license testers; test cards approve, decline or approve slowly; renewals are accelerated. Play Billing Lab resets trial eligibility. |

Library tests: `./gradlew :paywall-core:testDebugUnitTest :paywall-core:iosSimulatorArm64Test`.

## Migrating from 0.5.x

| 0.5.x (RevenueCat) | 1.0.0 |
|---|---|
| `RevenueCatConfig(apiKey, entitlementId, …)` | `BillingConfig(offerings, premiumProductIds)` |
| `configureRevenueCat(config)` at launch | create the platform `StoreClient`, then `billingManager.start()` at launch |
| `BillingManager(crashReporter)` | `BillingManager(config, store, crashReporter)` |
| `PaywallViewModel(config, billingManager, …)` | `PaywallViewModel(billingManager, …)` (no config) |
| RevenueCat dashboard offerings | `OfferingConfig` in the app: changing which plans a paywall shows needs an app update (prices still change in the stores) |
| `com.revenuecat.purchases.kmp.models.*` (`Offering`, `Package`, `PackageType`, `Price`, `Period`, `CustomerInfo`) | same names in `io.github.aleksandarrado93.paywall` |
| `PackageType.UNKNOWN`, `CUSTOM`; `PeriodUnit.UNKNOWN` | removed |
| `customerInfo.hasActivePremium(entitlementId)` | `customerInfo.hasActivePremium()` / `CustomerInfo.isPremium` |
| `PurchasesTransactionException` | `BillingException(code, message)`; cancel and pending are results, not exceptions |
| `PurchaseResult(transaction, customerInfo)` | sealed `PurchaseResult.Success` / `Pending` / `Cancelled` |
| `BillingManager.getEligibleFreeTrials(packages)` | removed: `StoreProduct.freeTrial` is already filtered |
| — | `PurchaseUiState.Pending` (show "payment pending") |
| — | `BillingManager.appAccountToken`: optional user id attached to purchases (UUID on iOS) for linking to your own accounts later |

Purchases live in Google Play and the App Store, not in RevenueCat, so existing subscribers keep premium after switching as long as the product ids stay the same.

## Why bridges instead of direct Firebase dependency?

The library is intentionally agnostic about your analytics/crash/persistence stack. You might use Firebase, you might use Amplitude or Sentry. By keeping these as bridges:

- The library has zero Firebase/Crashlytics/DataStore dependencies
- You can swap analytics providers without touching the library
- You can ship a "no-op" implementation in test builds via `NoOpPaywallAnalyticsBridge` and `NoOpPaywallCrashReporterBridge`

## Common pitfalls

### iOS Swift symbols and the `.ios.kt` filename suffix

If you call into Kotlin from Swift via the `*Kt` file accessor (e.g. `MyHelperKt.something()`), the file containing the `actual` function in `iosMain` should be named `MyHelper.kt` — **not** `MyHelper.ios.kt`. Kotlin/Native turns `MyHelper.ios.kt` into the Swift symbol `MyHelper_iosKt`, and your existing Swift call won't compile.

Either drop the `.ios.kt` suffix on files Swift directly calls into, or update the Swift caller to use `MyHelper_iosKt.something()`.

### Koin `viewModelOf(::PaywallViewModel)`

Prefer the explicit DSL. Reflection-based `viewModelOf` doesn't honor Kotlin default values, so any constructor parameter with a default (now or in a subclass) crashes at first paywall open with `NoDefinitionFoundException`:

```kotlin
viewModel {
    PaywallViewModel(
        billingManager = get(),
        premiumRepository = get(),
        analytics = get(),
        crashReporter = get(),
    )
}
```

### Forgetting `billingManager.start()`

Without it, the store listener only starts on the first billing call. Renewals, refunds and purchases that completed while the app was closed are then picked up late, and on iOS StoreKit warns about transactions nobody listened to.

## Versioning

Semantic versioning applies from 1.0.0. Pre-releases (`1.0.0-alphaNN`) may still change.

## Changelog

### 1.0.0-alpha01
- **Breaking:** replaced RevenueCat with native billing: Google Play Billing 8.0.0 (`AndroidStoreClient`) and StoreKit 2 through a Swift bridge (`IosStoreClient` + `ios/PaywallStoreKitBridge.swift`). See [Migrating from 0.5.x](#migrating-from-05x).
- Added `BillingConfig` / `OfferingConfig`, the `StoreClient` interface, `FakeStoreClient`, `BillingException`, `PurchaseUiState.Pending`, `BillingManager.start()` and `BillingManager.appAccountToken`.
- Own models replace the RevenueCat types (`Offering`, `Package`, `PackageType`, `StoreProduct`, `Price`, `Period`, `FreeTrial`, `CustomerInfo`, `StoreTransaction`).
- Unit tests for `BillingManager` and `PaywallViewModel` (Android host and iOS simulator).

### 0.5.0
- Updated RevenueCat KMP from 2.10.1 to 3.16.0 (native iOS SDK 5.98.0). Fixes the iOS build with Xcode 27, which rejects `PaywallColor.swift` in the iOS SDK 5.67.0 that 2.10.1 pinned.
- Requires Kotlin 2.3.20+.
- iOS consumers must remove the `PurchasesHybridCommon` / `PurchasesHybridCommonUI` Swift packages; the native SDK now comes with the KMP library.

### 0.4.0
- Added `FreeTrial` and `Package.freeTrial()` — trial length, price after the trial, and renewal period, read from the store product (Play Store default option free phase, App Store introductory free trial).
- Added `BillingManager.getEligibleFreeTrials(packages)` — returns only the trials the user can still get; App Store trials go through RevenueCat's intro eligibility check (`UNKNOWN` counts as not eligible).
- `PaywallUiState.freeTrials` — `PaywallViewModel` resolves eligible trials before publishing `PlansState.Ready`. Source-compatible: new field with a default.

### 0.3.0
- Added `BillingManager.getOffering(identifier)` — fetch any offering by its RevenueCat id, not just the `current` one. Enables promotional / discounted / win-back paywalls that live in a separate offering.
- Added `PaywallViewModel.purchasePackage(packageToPurchase, planLabel)` — purchase an already-resolved `Package` (including custom packages and packages from a non-current offering) through the same orchestration as `purchase(packageType)` (premium flag, analytics, `purchaseSuccess`, error mapping).

### 0.2.2
- Raised Android `minSdk` from 21 to 24 (Android 7.0 Nougat, ~98% device coverage). Matches the typical KMP-app minimum without forcing consumers onto a stricter floor.

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
