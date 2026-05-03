package io.github.aleksandarrado93.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revenuecat.purchases.kmp.models.Offering
import com.revenuecat.purchases.kmp.models.Package
import com.revenuecat.purchases.kmp.models.PackageType
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException
import io.github.aleksandarrado93.paywall.bridges.PaywallAnalyticsBridge
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.bridges.PremiumStateRepository
import io.github.aleksandarrado93.paywall.extensions.activePremiumProductId
import io.github.aleksandarrado93.paywall.extensions.hasActivePremium
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel that drives a single paywall screen. Combines
 * [PremiumStateRepository.isPremium] (the consumer app's cached premium flag)
 * with [BillingManager.customerInfo] (live RevenueCat state) and the in-flight
 * plan/purchase states.
 *
 * The ViewModel does NOT know about app-specific UI variants. The consumer app
 * defines its own `PaywallVariant` enum (or sealed type) and maps each variant
 * to a [PackageType] before calling [purchase]. Example:
 *
 * ```
 * enum class PaywallVariant(val packageType: PackageType) {
 *     Weekly(PackageType.WEEKLY),
 *     Yearly(PackageType.ANNUAL),
 * }
 *
 * fun onPlanClicked(variant: PaywallVariant) {
 *     viewModel.purchase(variant.packageType, planLabel = variant.name)
 * }
 * ```
 *
 * @param config The active [RevenueCatConfig] (for entitlement id resolution).
 * @param billingManager Wraps the RevenueCat SDK.
 * @param premiumRepository Persisted "is premium?" cache, written on success.
 * @param analytics Bridge for tracking conversion / failure events.
 * @param crashReporter Bridge for reporting non-fatal exceptions.
 * @param productLoadTimeoutMs Max time to wait for the offering fetch before
 *   falling back to [PlansState.Unavailable]. Defaults to 5 seconds.
 */
open class PaywallViewModel(
    config: RevenueCatConfig,
    private val billingManager: BillingManager,
    private val premiumRepository: PremiumStateRepository,
    private val analytics: PaywallAnalyticsBridge,
    private val crashReporter: PaywallCrashReporterBridge,
    private val productLoadTimeoutMs: Long = DEFAULT_PRODUCT_LOAD_TIMEOUT_MS,
) : ViewModel() {

    private val entitlementId = config.entitlementId

    private val _plansState = MutableStateFlow<PlansState>(PlansState.Loading)
    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)

    val uiState: StateFlow<PaywallUiState> = combine(
        premiumRepository.isPremium,
        billingManager.customerInfo,
        _plansState,
        _purchaseState,
    ) { isPremium, customerInfo, plans, purchase ->
        PaywallUiState(
            isPremium = isPremium,
            activePremiumProductId = customerInfo.activePremiumProductId(entitlementId),
            plans = plans,
            purchase = purchase,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_TIMEOUT_MS), PaywallUiState())

    private val _purchaseSuccess = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * One-shot signal so the screen can navigate away on success. Buffered so
     * the emit isn't lost if the collector is briefly unsubscribed during a
     * recomposition.
     */
    val purchaseSuccess: SharedFlow<Unit> = _purchaseSuccess.asSharedFlow()

    init {
        loadProducts()
    }

    fun loadProducts() {
        viewModelScope.launch {
            _plansState.value = PlansState.Loading
            try {
                val offering = withTimeoutOrNull(productLoadTimeoutMs) {
                    billingManager.refreshCustomerInfo()
                    billingManager.getCurrentOffering()
                }
                _plansState.value = if (offering != null) {
                    PlansState.Ready(offering)
                } else {
                    PlansState.Unavailable
                }
            } catch (e: Exception) {
                crashReporter.logNonFatal(e)
                _plansState.value = PlansState.Unavailable
            }
        }
    }

    /**
     * Resolves the [packageType] in the current offering and starts a purchase.
     * Falls back to a fresh offerings lookup if the cached state hasn't resolved
     * yet. Emits to [purchaseSuccess] on entitlement activation; otherwise
     * publishes a [PurchaseUiState.Error] to [uiState].
     *
     * @param packageType The RevenueCat package type to purchase.
     * @param planLabel A short label for analytics (e.g. "Weekly", "Yearly", "Trial").
     */
    fun purchase(packageType: PackageType, planLabel: String = packageType.name) {
        runPurchase(plan = planLabel) {
            val pkg = resolvePackage(packageType) ?: error(ERROR_PRODUCT_UNAVAILABLE)
            billingManager.purchase(pkg)
        }
    }

    private suspend fun resolvePackage(packageType: PackageType): Package? {
        val cached = (_plansState.value as? PlansState.Ready)?.offering
        cached?.packageOf(packageType)?.let { return it }
        return billingManager.getCurrentOffering()?.packageOf(packageType)
    }

    private fun runPurchase(plan: String, block: suspend () -> PurchaseResult) {
        viewModelScope.launch {
            _purchaseState.value = PurchaseUiState.Loading
            try {
                val result = block()
                handlePurchaseResult(plan = plan, isActive = result.customerInfo.hasActivePremium(entitlementId))
            } catch (e: PurchasesTransactionException) {
                handlePurchaseException(e)
            } catch (e: Exception) {
                analytics.purchaseFailed(errorType = e::class.simpleName ?: "unknown")
                _purchaseState.value = PurchaseUiState.Error(e.message ?: ERROR_PURCHASE_FAILED)
            }
        }
    }

    private suspend fun handlePurchaseResult(plan: String, isActive: Boolean) {
        if (!isActive) {
            analytics.purchaseFailed(errorType = "not_active")
            _purchaseState.value = PurchaseUiState.Error(ERROR_PURCHASE_NOT_ACTIVE)
            return
        }
        premiumRepository.setPremium(true)
        analytics.subscriptionConverted(plan = plan)
        signalPurchaseSuccess()
    }

    private fun handlePurchaseException(e: PurchasesTransactionException) {
        if (e.userCancelled) {
            _purchaseState.value = PurchaseUiState.Idle
            return
        }
        analytics.purchaseFailed(errorType = e.code.name)
        _purchaseState.value = PurchaseUiState.Error(e.message)
    }

    fun restorePurchases() {
        viewModelScope.launch {
            _purchaseState.value = PurchaseUiState.Loading
            try {
                val info = billingManager.restorePurchases()
                if (info.hasActivePremium(entitlementId)) {
                    premiumRepository.setPremium(true)
                    signalPurchaseSuccess()
                } else {
                    _purchaseState.value = PurchaseUiState.Error(ERROR_NO_ACTIVE_SUBSCRIPTION)
                }
            } catch (e: Exception) {
                crashReporter.logNonFatal(e)
                _purchaseState.value = PurchaseUiState.Error(e.message ?: ERROR_RESTORE_FAILED)
            }
        }
    }

    private suspend fun signalPurchaseSuccess() {
        _purchaseState.value = PurchaseUiState.Success
        _purchaseSuccess.emit(Unit)
    }

    fun clearError() {
        _purchaseState.value = PurchaseUiState.Idle
    }

    private companion object {
        const val DEFAULT_PRODUCT_LOAD_TIMEOUT_MS = 5_000L
        const val STATE_TIMEOUT_MS = 5_000L
        const val ERROR_PRODUCT_UNAVAILABLE = "Product not available"
        const val ERROR_PURCHASE_FAILED = "Purchase failed"
        const val ERROR_PURCHASE_NOT_ACTIVE = "Purchase did not activate premium"
        const val ERROR_RESTORE_FAILED = "Restore failed"
        const val ERROR_NO_ACTIVE_SUBSCRIPTION = "No active subscription found"
    }
}

/**
 * Looks up a predefined package by [PackageType] without throwing, returning null
 * when the offering doesn't include that type.
 */
internal fun Offering.packageOf(type: PackageType): Package? = when (type) {
    PackageType.ANNUAL -> annual
    PackageType.SIX_MONTH -> sixMonth
    PackageType.THREE_MONTH -> threeMonth
    PackageType.TWO_MONTH -> twoMonth
    PackageType.MONTHLY -> monthly
    PackageType.WEEKLY -> weekly
    PackageType.LIFETIME -> lifetime
    PackageType.UNKNOWN, PackageType.CUSTOM -> null
}
