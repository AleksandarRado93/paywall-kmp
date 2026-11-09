package io.github.aleksandarrado93.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aleksandarrado93.paywall.bridges.PaywallAnalyticsBridge
import io.github.aleksandarrado93.paywall.bridges.PaywallCrashReporterBridge
import io.github.aleksandarrado93.paywall.bridges.PremiumStateRepository
import io.github.aleksandarrado93.paywall.extensions.activePremiumProductId
import io.github.aleksandarrado93.paywall.store.BillingException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel that drives a single paywall screen. Combines
 * [PremiumStateRepository.isPremium] (the consumer app's cached premium flag)
 * with [BillingManager.customerInfo] (live store state) and the in-flight
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
 * The product load timeout defaults to 5 seconds. To customize it, subclass
 * [PaywallViewModel] and override [productLoadTimeoutMs]. This keeps the
 * primary constructor reflection-friendly so Koin's `viewModelOf(::PaywallViewModel)`
 * works without an explicit DSL block.
 *
 * @param billingManager Wraps the store.
 * @param premiumRepository Persisted "is premium?" cache, written on success.
 * @param analytics Bridge for tracking conversion / failure events.
 * @param crashReporter Bridge for reporting non-fatal exceptions.
 */
open class PaywallViewModel(
    private val billingManager: BillingManager,
    private val premiumRepository: PremiumStateRepository,
    private val analytics: PaywallAnalyticsBridge,
    private val crashReporter: PaywallCrashReporterBridge,
) : ViewModel() {

    /**
     * Max time to wait for the offering fetch before falling back to
     * [PlansState.Unavailable]. Override in a subclass to customize.
     */
    protected open val productLoadTimeoutMs: Long = DEFAULT_PRODUCT_LOAD_TIMEOUT_MS

    private val _plansState = MutableStateFlow<PlansState>(PlansState.Loading)
    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Idle)
    private val _freeTrials = MutableStateFlow<Map<String, FreeTrial>>(emptyMap())

    val uiState: StateFlow<PaywallUiState> = combine(
        premiumRepository.isPremium,
        billingManager.customerInfo,
        _plansState,
        _purchaseState,
        _freeTrials,
    ) { isPremium, customerInfo, plans, purchase, freeTrials ->
        PaywallUiState(
            isPremium = isPremium,
            activePremiumProductId = customerInfo.activePremiumProductId(),
            plans = plans,
            purchase = purchase,
            freeTrials = freeTrials,
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
                _freeTrials.value = offering?.availablePackages
                    ?.mapNotNull { pkg -> pkg.freeTrial()?.let { pkg.identifier to it } }
                    ?.toMap()
                    .orEmpty()
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
     * Falls back to a fresh offering lookup if the cached state hasn't resolved
     * yet. Emits to [purchaseSuccess] on premium activation; otherwise
     * publishes a [PurchaseUiState.Error] or [PurchaseUiState.Pending] to [uiState].
     *
     * @param packageType The plan to purchase.
     * @param planLabel A short label for analytics (e.g. "Weekly", "Yearly", "Trial").
     */
    fun purchase(packageType: PackageType, planLabel: String = packageType.name) {
        runPurchase(plan = planLabel) {
            val pkg = resolvePackage(packageType)
                ?: throw IllegalStateException(ERROR_PRODUCT_UNAVAILABLE)
            billingManager.purchase(pkg)
        }
    }

    /**
     * Starts a purchase for an already-resolved [packageToPurchase] (works for
     * packages that live in a non-current offering — see
     * [BillingManager.getOffering]). Shares the same orchestration as [purchase].
     *
     * @param packageToPurchase The exact package to buy.
     * @param planLabel A short label for analytics. Defaults to the package id.
     */
    fun purchasePackage(
        packageToPurchase: Package,
        planLabel: String = packageToPurchase.identifier,
    ) {
        runPurchase(plan = planLabel) {
            billingManager.purchase(packageToPurchase)
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
                when (val result = block()) {
                    is PurchaseResult.Success -> handlePurchaseSuccess(plan, result.customerInfo.isPremium)
                    PurchaseResult.Pending -> awaitPendingPurchase(plan)
                    PurchaseResult.Cancelled -> _purchaseState.value = PurchaseUiState.Idle
                }
            } catch (e: BillingException) {
                analytics.purchaseFailed(errorType = e.code.name)
                _purchaseState.value = PurchaseUiState.Error(e.message ?: ERROR_PURCHASE_FAILED)
            } catch (e: Exception) {
                analytics.purchaseFailed(errorType = e::class.simpleName ?: "unknown")
                _purchaseState.value = PurchaseUiState.Error(e.message ?: ERROR_PURCHASE_FAILED)
            }
        }
    }

    private suspend fun handlePurchaseSuccess(plan: String, isActive: Boolean) {
        if (!isActive) {
            analytics.purchaseFailed(errorType = "not_active")
            _purchaseState.value = PurchaseUiState.Error(ERROR_PURCHASE_NOT_ACTIVE)
            return
        }
        premiumRepository.setPremium(true)
        analytics.subscriptionConverted(plan = plan)
        signalPurchaseSuccess()
    }

    /** Shows [PurchaseUiState.Pending] and finishes the purchase if premium arrives while the paywall is open. */
    private suspend fun awaitPendingPurchase(plan: String) {
        _purchaseState.value = PurchaseUiState.Pending
        billingManager.customerInfo.drop(1).first { it?.isPremium == true }
        if (_purchaseState.value == PurchaseUiState.Pending) handlePurchaseSuccess(plan, isActive = true)
    }

    fun restorePurchases() {
        viewModelScope.launch {
            _purchaseState.value = PurchaseUiState.Loading
            try {
                val info = billingManager.restorePurchases()
                if (info.isPremium) {
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

    companion object {
        const val DEFAULT_PRODUCT_LOAD_TIMEOUT_MS = 5_000L
        private const val STATE_TIMEOUT_MS = 5_000L
        private const val ERROR_PRODUCT_UNAVAILABLE = "Product not available"
        private const val ERROR_PURCHASE_FAILED = "Purchase failed"
        private const val ERROR_PURCHASE_NOT_ACTIVE = "Purchase did not activate premium"
        private const val ERROR_RESTORE_FAILED = "Restore failed"
        private const val ERROR_NO_ACTIVE_SUBSCRIPTION = "No active subscription found"
    }
}
