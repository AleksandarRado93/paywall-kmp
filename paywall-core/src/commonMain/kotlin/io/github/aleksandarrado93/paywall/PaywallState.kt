package io.github.aleksandarrado93.paywall

/**
 * Coarse states for the in-flight purchase action. Drives loading spinners,
 * success navigation, and error toasts on the paywall UI.
 */
sealed interface PurchaseUiState {
    data object Idle : PurchaseUiState
    data object Loading : PurchaseUiState
    data object Success : PurchaseUiState

    /**
     * The store accepted the purchase but the payment isn't complete yet (cash
     * payment, Ask to Buy). Premium unlocks by itself once it completes.
     */
    data object Pending : PurchaseUiState
    data class Error(val message: String) : PurchaseUiState
}

/**
 * State of the offering / plans fetch. [Loading] until the first
 * [BillingManager.getCurrentOffering] resolves; [Unavailable] when no product of
 * the current offering loaded (store unreachable, product ids not set up in the
 * store, or not signed in to a store account). UI should disable the Subscribe
 * button when [Unavailable].
 */
sealed interface PlansState {
    data object Loading : PlansState
    data class Ready(val offering: Offering) : PlansState
    data object Unavailable : PlansState
}

/**
 * Aggregated paywall UI state, exposed by [PaywallViewModel.uiState]. Combines
 * the consumer app's persisted premium flag, the live [CustomerInfo], the
 * current [PlansState], the in-flight [PurchaseUiState], and the user's free trials.
 */
data class PaywallUiState(
    val isPremium: Boolean = false,
    val activePremiumProductId: String? = null,
    val plans: PlansState = PlansState.Loading,
    val purchase: PurchaseUiState = PurchaseUiState.Idle,
    /**
     * Free trials this user can get, keyed by [Package.identifier]. Empty until
     * plans are [PlansState.Ready]. Show trial copy only for packages present here.
     */
    val freeTrials: Map<String, FreeTrial> = emptyMap(),
)
