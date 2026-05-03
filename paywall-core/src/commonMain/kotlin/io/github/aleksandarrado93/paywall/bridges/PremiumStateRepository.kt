package io.github.aleksandarrado93.paywall.bridges

import kotlinx.coroutines.flow.Flow

/**
 * Bridge for the consumer app's persisted premium-state cache (typically backed
 * by DataStore, SharedPreferences, or NSUserDefaults). The library reads this to
 * combine with live RevenueCat state and writes to it after successful purchases
 * so the rest of the app can react without a network round-trip.
 *
 * Implementations must be thread-safe. The Flow should emit the current value
 * immediately on subscription (use a StateFlow or DataStore-style flow).
 */
interface PremiumStateRepository {
    val isPremium: Flow<Boolean>
    suspend fun setPremium(isPremium: Boolean)
}
