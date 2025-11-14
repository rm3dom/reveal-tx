@file:MustUseReturnValue

package com.swiftleap.tx.chaos

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Lightweight chaos/latency injection utilities used to stress‑test retryability and
 * transactional behaviour.
 *
 * How it works:
 * - Configure once via [chaos] with an [enabled] flag and a deterministic [seed].
 * - Create a [ChaosKey] using a named [ChaosProfile] to describe behaviour
 *   (latency range, error rate, and a cap on the number of errors).
 * - Call one of the [injectChaos] helpers at interesting points in your code. Depending on
 *   the profile and the RNG, the helper may delay for a random duration and/or throw a
 *   [ChaosException].
 *
 * Determinism:
 * Profiles draw randomness from a PRNG seeded with [ChaosConfig.seed]. Use fixed seeds in tests
 * to make scenarios reproducible.
 *
 * Thread‑safety:
 * Random draws are synchronized to keep the sequence deterministic under concurrency, and
 * error counts are maintained with atomic primitives.
 */
interface ChaosConfig {
    /** Enables or disables chaos injection globally. Defaults to false. */
    var enabled: Boolean

    /** Seed used by the internal PRNG to make chaos deterministic across runs. */
    var seed: Long
}

/** Internal, process‑wide configuration backing [chaos]. */
@PublishedApi
internal object Config : ChaosConfig {
    override var enabled: Boolean = false
    override var seed: Long = 0
}

/** Exception thrown by [injectChaos] when a profile elects to inject an error. */
class ChaosException(message: String) : Exception(message)

/** Marker interface representing a preconfigured chaos site in code. */
interface ChaosKey

/**
 * Describes how chaos should be injected for a particular [ChaosKey].
 *
 * @property name A descriptive name for logs and error messages.
 * @property minLatency Minimum latency to inject when latency is enabled.
 * @property maxLatency Maximum latency to inject when latency is enabled. Must be >= [minLatency].
 * @property errorRate Probability (0.0..1.0) that an error is injected per visit while under
 *                     [maxErrors].
 * @property maxErrors Upper bound on the total number of errors to inject. 0 disables errors.
 */
data class ChaosProfile(
    val name: String,
    val minLatency: Duration = Duration.ZERO,
    val maxLatency: Duration = Duration.ZERO,
    val errorRate: Double = 0.0,
    val maxErrors: Int = 0,
) {
    init {
        require(minLatency <= maxLatency) {
            "Invalid latency range"
        }
    }

    /** Useful preset profiles. */
    companion object {
        /** Injects an error with ~10% probability, unlimited errors. */
        val FAIL_10 = ChaosProfile("_FAIL_10_", Duration.ZERO, Duration.ZERO, 0.10, Int.MAX_VALUE)

        /** Injects an error with ~25% probability, unlimited errors. */
        val FAIL_25 = ChaosProfile("_FAIL_25_", Duration.ZERO, Duration.ZERO, 0.25, Int.MAX_VALUE)

        /** Injects exactly one error on the first visit. */
        val FAIL_ONCE = ChaosProfile("_FAIL_ONCE_", Duration.ZERO, Duration.ZERO, 1.0, 1)

        /** No latency, no errors. */
        val DISABLED = ChaosProfile("_DISABLED_")
    }
}

/**
 * Configures global chaos behaviour.
 *
 * Example:
 * ```kotlin
 * chaos {
 *   enabled = true
 *   seed = 42
 * }
 * ```
 */
fun chaos(block: ChaosConfig.() -> Unit) {
    Config.block()
}

/** Creates a [ChaosKey] bound to a [name] and [profile]. */
fun ChaosKey(name: String, profile: ChaosProfile): ChaosKey =
    ChaosKeyState(name, profile)

/**
 * Internal state that tracks error counts and produces deterministic random draws for a key.
 */
@PublishedApi
internal class ChaosKeyState(val name: String, val profile: ChaosProfile) : ChaosKey {
    private val errorCount = atomic(0)

    private val seededRandom = Random(Config.seed)
    private val lock = SynchronizedObject()

    private fun nextDouble(): Double {
        return synchronized(lock) {
            seededRandom.nextDouble(0.0, 1.0)
        }
    }

    /**
     * Computes whether to inject an error and how much latency to add for the next visit.
     * Returns a pair of (shouldInjectError, latency).
     */
    fun visit(): Pair<Boolean, Duration> {
        if (profile.maxErrors == 0 && profile.maxLatency == Duration.ZERO)
            return false to Duration.ZERO

        //Latency
        val latency: Duration = if (profile.maxLatency > Duration.ZERO) {
            val delta = profile.maxLatency - profile.minLatency
            profile.minLatency + (delta * nextDouble())
        } else {
            Duration.ZERO
        }

        //Error
        val shouldInjectError: Boolean

        //Roll the dice
        val odds = nextDouble()

        if (profile.errorRate > 0.0 && profile.maxErrors > 0) {
            // Error injection relies solely on the random roll, leading to accurate
            // probabilistic error rates until maxErrors is hit.
            if (errorCount.value < profile.maxErrors && odds <= profile.errorRate) {
                errorCount.incrementAndGet()
                shouldInjectError = true
            } else {
                shouldInjectError = false
            }
        } else {
            shouldInjectError = false
        }

        return shouldInjectError to latency
    }

    /** Builds a descriptive [ChaosException] for this key/profile. */
    fun createException() =
        ChaosException("Chaos error injected by '${name}' using profile '${profile.name}' and seed '${Config.seed}'")
}

/**
 * Injects chaos for a given [key]. If the key elects to inject an error, the [error]
 * handler is invoked (defaults to throwing). Otherwise, a latency is injected via [delay].
 *
 * No‑op when chaos is disabled or [key] is null.
 */
suspend inline fun injectChaos(key: ChaosKey?, error: (ex: ChaosException) -> Nothing = { throw it }) {
    if (!Config.enabled || key == null) return
    val key = key as ChaosKeyState
    val (err, latency) = key.visit()
    if (err) error(key.createException())
    delay(latency)
}

/**
 * Surrounds [block] with chaos injection before execution. If an error is injected,
 * the [error] handler is invoked and its result is returned.
 *
 * No‑op when chaos is disabled or [key] is null.
 */
suspend inline fun <E> injectChaos(
    key: ChaosKey?,
    error: (ex: ChaosException) -> E = { throw it },
    block: suspend () -> E
): E {
    if (!Config.enabled || key == null) return block()
    val key = key as ChaosKeyState
    val (err, latency) = key.visit()
    return if (err)
        error(key.createException())
    else {
        delay(latency)
        block()
    }
}

/**
 * Executes [block] and then applies chaos. If an error is injected, the block’s result
 * is discarded and [error] is invoked.
 *
 * No‑op when chaos is disabled or [key] is null.
 */
suspend inline fun <E> injectChaosAfter(
    key: ChaosKey?,
    error: (ex: ChaosException) -> E = { throw it },
    block: suspend () -> E
): E {
    if (!Config.enabled || key == null) return block()
    val key = key as ChaosKeyState
    val (err, latency) = key.visit()
    return if (err) {
        val _ = block()
        error(key.createException())
    } else {
        delay(latency)
        block()
    }
}
