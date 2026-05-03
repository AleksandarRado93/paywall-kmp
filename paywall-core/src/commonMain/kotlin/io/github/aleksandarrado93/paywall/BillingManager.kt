package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.ktx.awaitCustomerInfo
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.Package
import io.github.aleksandarrado93.paywall.bridges.NoOpPaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin coroutine wrapper around the RevenueCat KMP SDK. Owns the latest known
 * [CustomerInfo] as a [StateFlow] so consumers can react to entitlement changes
 * without polling.
 *
 * RevenueCat must be initialized via [configureRevenueCat] before any method on
 * this manager is called — typically in [android.app.Application.onCreate] on
 * Android and `iOSApp.swift` on iOS. If RevenueCat was not initialized
 * (invalid API key, init failed), all suspending methods will catch the
 * resulting exception and report it through [crashReporter] instead of crashing.
 *
 * @param crashReporter Bridge for reporting non-fatal exceptions. Defaults to
 *   [NoOpPaywallCrashReporterBridge] for early bring-up.
 */
class BillingManager(
    private val crashReporter: PaywallCrashReporterBridge = NoOpPaywallCrashReporterBridge,
) {

    private val _customerInfo = MutableStateFlow<CustomerInfo?>(null)
    val customerInfo: StateFlow<CustomerInfo?> = _customerInfo.asStateFlow()

    suspend fun refreshCustomerInfo(): CustomerInfo? {
        return try {
            val info = Purchases.sharedInstance.awaitCustomerInfo()
            _customerInfo.value = info
            info
        } catch (e: Exception) {
            crashReporter.logNonFatal(e)
            null
        }
    }

    suspend fun getCurrentOffering(): Offering? {
        return try {
            Purchases.sharedInstance.awaitOfferings().current
        } catch (e: Exception) {
            crashReporter.logNonFatal(e)
            null
        }
    }

    /**
     * Starts a purchase for [packageToPurchase]. Throws on failure — callers
     * (typically [PaywallViewModel]) are expected to catch
     * [com.revenuecat.purchases.kmp.models.PurchasesTransactionException] to
     * distinguish user-cancellation from genuine errors.
     */
    suspend fun purchase(packageToPurchase: Package): PurchaseResult {
        val result = Purchases.sharedInstance.awaitPurchase(packageToPurchase)
        _customerInfo.value = result.customerInfo
        return PurchaseResult(
            transaction = result.storeTransaction,
            customerInfo = result.customerInfo,
        )
    }

    suspend fun restorePurchases(): CustomerInfo {
        val info = Purchases.sharedInstance.awaitRestore()
        _customerInfo.value = info
        return info
    }
}
