package io.github.aleksandarrado93.paywall

import com.revenuecat.purchases.kmp.models.Offering

/**
 * Coarse states for the in-flight purchase action. Drives loading spinners,
 * success navigation, and error toasts on the paywall UI.
 */
sealed interface PurchaseUiState {
    data object Idle : PurchaseUiState
    data object Loading : PurchaseUiState
    data object Success : PurchaseUiState
    data class Error(val message: String) : PurchaseUiState
}

/**
 * State of the offering / plans fetch. [Loading] until the first
 * [BillingManager.getCurrentOffering] resolves; [Unavailable] when RevenueCat
 * returns no current offering (misconfigured dashboard, no products attached,
 * or RC was never initialized). UI should disable the Subscribe button when
 * [Unavailable].
 */
sealed interface PlansState {
    data object Loading : PlansState
    data class Ready(val offering: Offering) : PlansState
    data object Unavailable : PlansState
}

/**
 * Aggregated paywall UI state, exposed by [PaywallViewModel.uiState]. Combines
 * the consumer app's persisted premium flag, the live RevenueCat
 * [com.revenuecat.purchases.kmp.models.CustomerInfo], the current [PlansState],
 * the in-flight [PurchaseUiState], and the user's eligible free trials.
 */
data class PaywallUiState(
    val isPremium: Boolean = false,
    val activePremiumProductId: String? = null,
    val plans: PlansState = PlansState.Loading,
    val purchase: PurchaseUiState = PurchaseUiState.Idle,
    /**
     * Free trials this user can get, keyed by [com.revenuecat.purchases.kmp.models.Package.identifier].
     * Empty until plans are [PlansState.Ready]. Show trial copy only for
     * packages present here — see [BillingManager.getEligibleFreeTrials].
     */
    val freeTrials: Map<String, FreeTrial> = emptyMap(),
)
