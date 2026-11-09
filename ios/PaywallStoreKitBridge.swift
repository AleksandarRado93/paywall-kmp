// StoreKit 2 implementation of paywall-kmp's `StoreKitBridge`.
//
// Copy this file into your iOS app target(s) and change the import below if
// your Kotlin framework isn't called `ComposeApp`. The framework must export
// paywall-core (`export(libs.paywall.core)`) so the Kotlin types keep their
// plain names. Then, at launch:
//
//     let storeClient = IosStoreClient(bridge: PaywallStoreKitBridge())
//
// and hand `storeClient` to the Kotlin `BillingManager`.

import Foundation
import StoreKit
import ComposeApp

final class PaywallStoreKitBridge: NSObject, StoreKitBridge {

    private var updatesTask: Task<Void, Never>?
    private var productsById: [String: Product] = [:]
    private let lock = NSLock()

    deinit {
        updatesTask?.cancel()
    }

    func start(onTransactionsChanged: @escaping () -> Void) {
        guard updatesTask == nil else { return }
        updatesTask = Task.detached(priority: .background) {
            for await result in Transaction.updates {
                // Renewals, refunds, Ask to Buy approvals and purchases made on other devices.
                if case .verified(let transaction) = result {
                    await transaction.finish()
                }
                onTransactionsChanged()
            }
        }
        Task.detached(priority: .background) {
            // Purchases that completed while the app was closed.
            for await result in Transaction.unfinished {
                if case .verified(let transaction) = result {
                    await transaction.finish()
                }
            }
            onTransactionsChanged()
        }
    }

    func loadProducts(productIds: [String], onResult: @escaping ([StoreProduct], String?) -> Void) {
        Task {
            do {
                let products = try await Product.products(for: productIds)
                lock.withLock {
                    for product in products { productsById[product.id] = product }
                }
                var mapped: [StoreProduct] = []
                for product in products {
                    mapped.append(await Self.storeProduct(from: product))
                }
                onResult(mapped, nil)
            } catch {
                onResult([], error.localizedDescription)
            }
        }
    }

    func purchase(
        productId: String,
        appAccountToken: String?,
        onResult: @escaping (StoreKitPurchaseResult) -> Void
    ) {
        Task {
            do {
                var product = lock.withLock { productsById[productId] }
                if product == nil {
                    product = try await Product.products(for: [productId]).first
                }
                guard let product else {
                    onResult(Self.result(.failed, error: "Product not available"))
                    return
                }
                var options: Set<Product.PurchaseOption> = []
                if let token = appAccountToken.flatMap(UUID.init(uuidString:)) {
                    options.insert(.appAccountToken(token))
                }
                switch try await product.purchase(options: options) {
                case .success(.verified(let transaction)):
                    await transaction.finish()
                    onResult(Self.result(.purchased, transaction: Self.storeTransaction(from: transaction)))
                case .success(.unverified(_, let error)):
                    onResult(Self.result(.verificationFailed, error: error.localizedDescription))
                case .pending:
                    onResult(Self.result(.pending))
                case .userCancelled:
                    onResult(Self.result(.cancelled))
                @unknown default:
                    onResult(Self.result(.failed, error: "Unknown purchase result"))
                }
            } catch {
                onResult(Self.result(.failed, error: error.localizedDescription))
            }
        }
    }

    func activePurchases(onResult: @escaping ([StoreTransaction], String?) -> Void) {
        Task {
            var purchases: [StoreTransaction] = []
            for await result in Transaction.currentEntitlements {
                guard case .verified(let transaction) = result, transaction.revocationDate == nil else { continue }
                purchases.append(Self.storeTransaction(from: transaction))
            }
            onResult(purchases, nil)
        }
    }

    func restore(onResult: @escaping (String?) -> Void) {
        Task {
            do {
                try await AppStore.sync()
                onResult(nil)
            } catch {
                onResult(error.localizedDescription)
            }
        }
    }

    // MARK: - Mapping

    private static func storeProduct(from product: Product) async -> StoreProduct {
        let price = Price(
            amountMicros: micros(product.price),
            currencyCode: product.priceFormatStyle.currencyCode,
            formatted: product.displayPrice
        )
        var trial: FreeTrial?
        if let subscription = product.subscription,
           let intro = subscription.introductoryOffer,
           intro.paymentMode == .freeTrial,
           await subscription.isEligibleForIntroOffer {
            let one = period(intro.period)
            trial = FreeTrial(
                period: Period(value: one.value * Int32(max(intro.periodCount, 1)), unit: one.unit),
                priceAfterTrial: price,
                billingPeriod: period(subscription.subscriptionPeriod)
            )
        }
        return StoreProduct(
            id: product.id,
            price: price,
            period: product.subscription.map { period($0.subscriptionPeriod) },
            freeTrial: trial
        )
    }

    private static func storeTransaction(from transaction: Transaction) -> StoreTransaction {
        StoreTransaction(
            productId: transaction.productID,
            transactionId: String(transaction.id),
            purchaseTimeMillis: millis(transaction.purchaseDate),
            expirationTimeMillis: transaction.expirationDate.map { KotlinLong(value: millis($0)) }
        )
    }

    private static func period(_ period: Product.SubscriptionPeriod) -> Period {
        let unit: PeriodUnit
        switch period.unit {
        case .day: unit = .day
        case .week: unit = .week
        case .month: unit = .month
        case .year: unit = .year
        @unknown default: unit = .day
        }
        return Period(value: Int32(period.value), unit: unit)
    }

    private static func result(
        _ status: StoreKitPurchaseStatus,
        transaction: StoreTransaction? = nil,
        error: String? = nil
    ) -> StoreKitPurchaseResult {
        StoreKitPurchaseResult(status: status, transaction: transaction, errorMessage: error)
    }

    private static func micros(_ decimal: Decimal) -> Int64 {
        NSDecimalNumber(decimal: decimal * 1_000_000).int64Value
    }

    private static func millis(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000)
    }
}
