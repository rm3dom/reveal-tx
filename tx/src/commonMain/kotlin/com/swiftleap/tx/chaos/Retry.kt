@file:MustUseReturnValue

package com.swiftleap.tx.chaos

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configures the retry behavior for operations that may fail transiently.
 *
 * @property retryableExceptions Set of exception types that should trigger a retry
 * @property maxAttempts Maximum number of attempts before giving up (default: 3)
 * @property initialDelay Initial delay between retry attempts (default: 50ms)
 * @property maxDelay Maximum delay between retry attempts (default: 1s)
 * @property multiplier Factor by which the delay increases between retries (default: 2.0)
 * @property jitter Random factor to add to delay to prevent synchronized retries (default: 0.2)
 */
class RetryPolicy(
    val retryableExceptions: Set<KClass<out Throwable>>,
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 50.milliseconds,
    val maxDelay: Duration = 1.seconds,
    val multiplier: Double = 2.0,
    val jitter: Double = 0.2,
) {
    init {
        require(maxAttempts >= 1) { "retryPolicy.maxAttempts must be >= 1" }
        require(multiplier >= 1.0) { "retryPolicy.multiplier must be >= 1.0" }
        require(jitter in 0.0..1.0) { "retryPolicy.jitter must be in [0,1]" }
    }

    internal fun isRetryableException(t: Throwable): Boolean =
        retryableExceptions.any { it.isInstance(t) }

    internal fun computeBackoffDelay(
        attempt: Int,
        initial: Duration,
        max: Duration,
        multiplier: Double,
        jitter: Double,
    ): Duration {
        // attempt is 1-based
        val factor = multiplier.pow((attempt - 1).toDouble())
        val baseMillis = initial.inWholeMilliseconds * factor
        val capped = min(baseMillis, max.inWholeMilliseconds.toDouble())
        val jitterPortion = capped * jitter
        val low = capped - jitterPortion
        val high = capped + jitterPortion
        val chosen = if (high > low) Random.nextDouble(low, high) else capped
        val millis = chosen.coerceAtLeast(0.0).toLong()
        return millis.milliseconds
    }
}

/**
 * Executes a suspending operation with retry behavior based on the provided policy.
 *
 * @param policy The retry policy configuration
 * @param block The suspending operation to execute
 * @param T The return type of the operation
 * @return The result of the operation if successful
 * @throws CancellationException if the coroutine is canceled
 * @throws Throwable The last error encountered after all retry attempts are exhausted
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
@Throws(Throwable::class, CancellationException::class)
suspend fun <T> retrySuspending(policy: RetryPolicy, block: suspend () -> T): T {
    var lastError: Throwable? = null
    var attempt = 0
    while (attempt <= policy.maxAttempts) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = t
            if (policy.isRetryableException(t) && attempt < policy.maxAttempts) {
                val delayDuration = policy.computeBackoffDelay(
                    attempt = attempt,
                    initial = policy.initialDelay,
                    max = policy.maxDelay,
                    multiplier = policy.multiplier,
                    jitter = policy.jitter,
                )
                delay(delayDuration)
                attempt++
                continue
            } else {
                throw t
            }
        }
    }
    throw lastError!!
}
