package io.github.aleksandarrado93.paywall.store

import io.github.aleksandarrado93.paywall.StoreProduct
import io.github.aleksandarrado93.paywall.StoreTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory store for unit tests and for debug builds before store accounts
 * exist. Every purchase follows [nextPurchase]; set it to simulate a decline,
 * cancel or pending payment.
 *
 * @param products What [loadProducts] can return.
 * @param latencyMs Artificial delay on each call, so loading states are visible.
 */
class FakeStoreClient(
    var products: List<StoreProduct> = emptyList(),
    var latencyMs: Long = 0,
) : StoreClient {

    /** What the next [purchase] call does. */
    sealed interface NextPurchase {
        data object Succeed : NextPurchase
        data object Cancel : NextPurchase
        data object Pend : NextPurchase
        data class Fail(val code: BillingErrorCode, val message: String) : NextPurchase
    }

    var nextPurchase: NextPurchase = NextPurchase.Succeed

    /** Purchases the fake store currently grants. Change it, then call [emitUpdate]. */
    val owned: MutableList<StoreTransaction> = mutableListOf()

    /** When set, [loadProducts], [activePurchases] and [restore] throw this. */
    var failure: BillingException? = null

    var purchaseCount: Int = 0
        private set

    private var transactionCounter = 0L
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override val purchaseUpdates: Flow<Unit> = updates.asSharedFlow()

    override fun start() = Unit

    override suspend fun loadProducts(productIds: List<String>): List<StoreProduct> {
        simulate()
        return products.filter { it.id in productIds }
    }

    override suspend fun purchase(product: StoreProduct, appAccountToken: String?): StorePurchaseOutcome {
        delay(latencyMs)
        purchaseCount++
        return when (val next = nextPurchase) {
            NextPurchase.Succeed -> {
                val transaction = StoreTransaction(
                    productId = product.id.substringBefore(':'),
                    transactionId = "fake-${++transactionCounter}",
                    purchaseTimeMillis = 0L,
                    expirationTimeMillis = null,
                )
                owned += transaction
                StorePurchaseOutcome.Purchased(transaction)
            }
            NextPurchase.Cancel -> StorePurchaseOutcome.Cancelled
            NextPurchase.Pend -> StorePurchaseOutcome.Pending
            is NextPurchase.Fail -> throw BillingException(next.code, next.message)
        }
    }

    override suspend fun activePurchases(): List<StoreTransaction> {
        simulate()
        return owned.toList()
    }

    override suspend fun restore(): List<StoreTransaction> = activePurchases()

    /** Simulates a renewal, refund or approved payment arriving from the store. */
    fun emitUpdate() {
        updates.tryEmit(Unit)
    }

    private suspend fun simulate() {
        delay(latencyMs)
        failure?.let { throw it }
    }
}
