/**
 * JPA integration for the `tx` core primitives.
 *
 * This module adapts the guarantees and assertions from `com.swiftleap.tx` to JPA.
 * It provides small interfaces and a factory that:
 * - starts read/write work units backed by an `EntityManager`
 * - applies `TxAssert` rules (tx-never-nest) before opening a transaction
 * - propagates a logical transaction stack through the coroutine context
 * - exposes the active `EntityManagerFactory` (and optionally `EntityManager`) to code inside the block
 *
 * Notes and guarantees (see README and `tx` module for definitions):
 * - tx-read / tx-write colouring via `JpaReader` / `JpaWriter`
 * - tx-never-nest: explicit nested transactions are forbidden by design here
 * - Isolation level passed to `TxAssert` is used for the rule checks; mapping the JDBC
 *   isolation to the actual JPA provider/connection is left as a TODO and may depend on
 *   the persistence provider and configuration (RESOURCE_LOCAL vs JTA).
 */
@file:MustUseReturnValue

package com.swiftleap.tx.jpa

import com.swiftleap.tx.*
import com.swiftleap.tx.chaos.RetryPolicy
import com.swiftleap.tx.chaos.retrySuspending
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import jakarta.persistence.FlushModeType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default configuration for the JPA transaction factory.
 *
 * @property defaultTimeout Desired timeout for a transaction if/when supported by the provider.
 *                          Currently stored for future use. Defaults to 30 seconds.
 * @property defaultIsolationLevel Default JDBC isolation used when none is specified in [TxOption].
 * @property retryPolicy Policy for retrying transactions on transient failures (e.g. SQLException).
 * @property postCondition Optional post-transaction hook that can be used to perform chaos
 *                         injections or additional validations.
 */
@JvmRecord
data class TxConfiguration(
    val defaultTimeout: Duration = 30.seconds,
    val defaultIsolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED,
    val retryPolicy: RetryPolicy = RetryPolicy(setOf(SQLException::class)),
    val postCondition: (suspend (em: EntityManager, result: Any?) -> Unit)? = null,
)

/**
 * Reader role inside a JPA-backed unit of work.
 *
 * Conveys tx-read intent across the call stack.
 */
interface JpaReader<Db> : TxReader<Db> {
    /**
     * The underlying [EntityManager].
     */
    val entityManager: EntityManager
}

/**
 * Writer role inside a JPA-backed unit of work.
 *
 * Implies tx-write (and therefore tx-read). Provides the same JPA accessors.
 */
interface JpaWriter<Db> : TxWriter<Db>, JpaReader<Db>

/** Starts read units of work with a `JpaReader` receiver. */
interface JpaReadFactory<Db> : TxReadFactory<JpaReader<Db>>

/**
 * Factory that executes read/write units of work using JPA EntityManager/EntityTransaction.
 *
 * @param emf Target [EntityManagerFactory].
 * @param configuration Defaults used when options are not provided.
 *
 * Behaviour:
 * - Applies `TxAssert.txNeverNest` prior to opening a transaction.
 * - Pushes a logical frame to the coroutine context for the duration of the work.
 * - Creates/Closes an `EntityManager` per call; for write, begin/commit/rollback transaction.
 * - For read, no explicit JPA transaction is started (provider dependent); code must be read-only.
 * - Asserts that `Result` types are not leaked out of the block (force unboxing).
 */
open class JpaFactory<Db>(
    private val emf: EntityManagerFactory,
    private val configuration: TxConfiguration = TxConfiguration()
) : TxFactory<JpaReader<Db>, JpaWriter<Db>>, JpaReadFactory<Db> {

    /**
     * Core executor used by both read and write variants.
     *
     * - Validates tx-never-nest using [TxAssert.txNeverNest].
     * - Derives isolation level from [option] or defaults.
     * - Builds a coroutine context that carries a transaction frame (readOnly + isolation).
     * - Manages `EntityManager` and (for write) `EntityTransaction` lifecycle.
     */
    @Suppress("CognitiveComplexMethod", "TooGenericExceptionCaught")
    private suspend fun <T> execute(
        readOnly: Boolean,
        option: TxOption? = null,
        block: suspend JpaWriter<Db>.() -> T
    ): T {
        with(TxAssert) {
            val transactionIsolation = option?.isolationLevel ?: configuration.defaultIsolationLevel
            txNeverNest(readOnly, transactionIsolation)

            val timeout = option?.timeout ?: configuration.defaultTimeout

            val txFrame = TxAssert.TxContextStackElement(readOnly, transactionIsolation)

            var baseContext = currentCoroutineContext() + txFrame
            option?.context?.let { baseContext += it }

            val policy = configuration.retryPolicy

            return retrySuspending(policy) {
                val context = baseContext
                withContext(context) {
                    val em = emf.createEntityManager()
                    val writer = object : JpaWriter<Db> {
                        override val entityManager: EntityManager = em
                    }
                    try {
                        val result: T = if (readOnly) {
                            // Some providers support read-only hints/flush modes.
                            val prevFlushMode = em.flushMode
                            try {
                                // Avoid accidental flushes in read blocks.
                                em.flushMode = FlushModeType.COMMIT
                                val r = block(writer)
                                configuration.postCondition?.invoke(em, r)
                                r
                            } finally {
                                em.flushMode = prevFlushMode
                            }
                        } else {
                            val tx: EntityTransaction = em.transaction
                            tx.timeout = timeout.inWholeSeconds.toInt()
                            try {
                                tx.begin()
                                val r = block(writer)
                                configuration.postCondition?.invoke(em, r)
                                tx.commit()
                                r
                            } catch (e: Throwable) {
                                if (tx.isActive) try {
                                    tx.rollback()
                                } catch (_: Throwable) {
                                }
                                throw e
                            }
                        }

                        // Encourage callers to unwrap Result inside the transaction to avoid leaking laziness.
                        assert(result !is Result<*>) {
                            "Result types must be unboxed before returning from a transaction"
                        }

                        result
                    } finally {
                        em.close()
                    }
                }
            }
        }
    }

    /** Executes a read-only unit of work and provides a [JpaReader] receiver. */
    @TxDsl
    override suspend fun <T> executeRead(option: TxOption?, block: suspend JpaReader<Db>.() -> T): T =
        execute(readOnly = true, option = option, block)

    /** Executes a write unit of work and provides a [JpaWriter] receiver. Obeys tx-never-nest. */
    @TxDsl
    @TxNever
    override suspend fun <T> executeWrite(option: TxOption?, block: suspend JpaWriter<Db>.() -> T): T =
        execute(readOnly = false, option = option, block)
}

