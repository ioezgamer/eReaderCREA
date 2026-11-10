package org.crea.reader.utils

import timber.log.Timber

/**
 * Returns the result of the given [closure], or null if an [Exception] was raised.
 */
inline fun <T> tryOrNull(closure: () -> T): T? =
    tryOr(null, closure)

/**
 * Returns the result of the given [closure], or [default] if an [Exception] was raised.
 */
inline fun <T> tryOr(default: T, closure: () -> T): T =
    try {
        closure()
    } catch (e: Exception) {
        default
    }

/**
 * Returns the result of the given [closure], or null if an [Exception] was raised.
 * The [Exception] will be logged.
 */
inline fun <T> tryOrLog(closure: () -> T): T? =
    try {
        closure()
    } catch (e: Exception) {
        Timber.e(e)
        null
    }


/**
 * Like [runCatching], but a coroutine's [kotlinx.coroutines.CancellationException] is never caught:
 * catching it would keep a cancelled sync, download or screen running. Use it around suspend calls.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
