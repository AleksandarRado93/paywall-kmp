package io.github.aleksandarrado93.paywall.ios

import io.github.aleksandarrado93.paywall.StoreProduct
import io.github.aleksandarrado93.paywall.StoreTransaction
import io.github.aleksandarrado93.paywall.store.BillingErrorCode
import io.github.aleksandarrado93.paywall.store.BillingException
import io.github.aleksandarrado93.paywall.store.StoreClient
import io.github.aleksandarrado93.paywall.store.StorePurchaseOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [StoreClient] backed by StoreKit 2 through a Swift [StoreKitBridge]. */
class IosStoreClient(private val bridge: StoreKitBridge) : StoreClient {

    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var started = false

    override val purchaseUpdates: Flow<Unit> = updates.asSharedFlow()

    override fun start() {
        if (started) return
        started = true
        bridge.start { updates.tryEmit(Unit) }
    }

    override suspend fun loadProducts(productIds: List<String>): List<StoreProduct> =
        suspendCancellableCoroutine { cont ->
            bridge.loadProducts(productIds) { products, error ->
                if (!cont.isActive) return@loadProducts
                if (error != null) {
                    cont.resumeWithException(BillingException(BillingErrorCode.STORE_UNAVAILABLE, error))
                } else {
                    cont.resume(products)
                }
            }
        }

    override suspend fun purchase(product: StoreProduct, appAccountToken: String?): StorePurchaseOutcome =
        suspendCancellableCoroutine { cont ->
            bridge.purchase(product.id, appAccountToken) { result ->
                if (!cont.isActive) return@purchase
                val transaction = result.transaction
                when (result.status) {
                    StoreKitPurchaseStatus.PURCHASED if transaction != null ->
                        cont.resume(StorePurchaseOutcome.Purchased(transaction))
                    StoreKitPurchaseStatus.PENDING -> cont.resume(StorePurchaseOutcome.Pending)
                    StoreKitPurchaseStatus.CANCELLED -> cont.resume(StorePurchaseOutcome.Cancelled)
                    StoreKitPurchaseStatus.VERIFICATION_FAILED -> cont.resumeWithException(
                        BillingException(
                            BillingErrorCode.VERIFICATION_FAILED,
                            result.errorMessage ?: "The App Store could not verify this purchase",
                        ),
                    )
                    else -> cont.resumeWithException(
                        BillingException(BillingErrorCode.UNKNOWN, result.errorMessage ?: "Purchase failed"),
                    )
                }
            }
        }

    override suspend fun activePurchases(): List<StoreTransaction> =
        suspendCancellableCoroutine { cont ->
            bridge.activePurchases { purchases, error ->
                if (!cont.isActive) return@activePurchases
                if (error != null) {
                    cont.resumeWithException(BillingException(BillingErrorCode.STORE_UNAVAILABLE, error))
                } else {
                    cont.resume(purchases)
                }
            }
        }

    override suspend fun restore(): List<StoreTransaction> {
        suspendCancellableCoroutine { cont ->
            bridge.restore { error ->
                if (!cont.isActive) return@restore
                if (error != null) {
                    cont.resumeWithException(BillingException(BillingErrorCode.STORE_UNAVAILABLE, error))
                } else {
                    cont.resume(Unit)
                }
            }
        }
        return activePurchases()
    }
}
