package io.github.aleksandarrado93.paywall

/** The free trial this user can still get on this package, or null. */
fun Package.freeTrial(): FreeTrial? = storeProduct.freeTrial
