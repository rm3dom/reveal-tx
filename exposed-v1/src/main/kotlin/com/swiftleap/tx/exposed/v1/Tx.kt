/**
 * Exposed integration for the `tx` core primitives.
 *
 * This module adapts the guarantees and assertions from `com.swiftleap.tx` to JetBrains Exposed.
 * It provides small interfaces and a factory that:
 * - starts read/write transactions using Exposed’s `newSuspendedTransaction`
 * - applies `TxAssert` rules (tx-never-nest) before opening a transaction
 * - propagates a logical transaction stack through the coroutine context
 * - exposes the active Exposed `Transaction` to code inside the block
 *
 * Notes and guarantees (see README and `tx` module for definitions):
 * - tx-read / tx-write colouring via `ExposedReader` / `ExposedWriter`
 * - tx-never-nest: explicit nested transactions are forbidden by design here; the `Database`
 *   must not use database-managed nested transactions for this factory
 * - Isolation level passed to Exposed is used for the `tx-never-nest` rule checks
 */
@file:MustUseReturnValue

package com.swiftleap.tx.exposed.v1

import com.swiftleap.tx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

import java.sql.Connection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default configuration for the Exposed transaction factory.
 *
 * @property defaultTimeout Desired timeout for a transaction if the integration later supports it.
 *                          Currently stored for future use. Defaults to 30 seconds.
 * @property defaultIsolationLevel Default JDBC isolation used when none is specified in [TxOption].
 * @property postCondition Optional post-transaction hook that can be used to perform chaos
 *                         injections or additional validations.
 */
@JvmRecord
data class TxConfiguration(
    val defaultTimeout: Duration = 30.seconds,
    val defaultIsolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED,
    val postCondition: (suspend (tx: Transaction, result: Any?) -> Unit)? = null,
)

/**
 * Reader role inside an Exposed-backed transaction.
 *
 * Exposes the current Exposed [transaction] for callers that need low-level access.
 * Conveys tx-read intent across the call stack.
 */
interface ExposedReader<Db> : TxReader<Db> {
    val transaction: Transaction
}

/**
 * Writer role inside an Exposed-backed transaction.
 *
 * Implies tx-write (and therefore tx-read). Provides the same [transaction] access.
 */
interface ExposedWriter<Db> : TxWriter<Db>, ExposedReader<Db>

/** Starts read transactions with an `ExposedReader` receiver. */
interface ExposedReadFactory<Db> : TxReadFactory<ExposedReader<Db>>

/**
 * Factory that executes read/write transactions using JetBrains Exposed.
 *
 * @param database Target Exposed [Database]. Must have `useNestedTransactions == false`.
 * @param configuration Defaults used when options are not provided.
 *
 * Behaviour:
 * - Applies `TxAssert.txNeverNest` prior to opening a transaction.
 * - Pushes a logical frame to the coroutine context for the duration of the transaction.
 * - Creates a lightweight writer that exposes the current Exposed [Transaction].
 */
open class ExposedFactory<Db>(
    private val database: Database,
    private val configuration: TxConfiguration = TxConfiguration()
) : TxFactory<ExposedReader<Db>, ExposedWriter<Db>>, ExposedReadFactory<Db> {

    init {
        require(!database.useNestedTransactions) {
            "Nested transactions are not supported - explicit transaction boundaries are strictly enforced"
        }
    }

    /**
     * Core executor used by both read and write variants.
     *
     * - Validates tx-never-nest using [TxAssert.txNeverNest].
     * - Derives isolation level from [option] or defaults.
     * - Builds a coroutine context that carries a transaction frame (readOnly + isolation).
     * - Runs Exposed’s [newSuspendedTransaction] with the provided [database].
     */
    private suspend fun <T> execute(
        readOnly: Boolean,
        option: TxOption? = null,
        block: suspend ExposedWriter<Db>.() -> T
    ): T {
        with(TxAssert) {
            val transactionIsolation = option?.isolationLevel ?: configuration.defaultIsolationLevel
            txNeverNest(readOnly, transactionIsolation)

            val timeout = option?.timeout ?: configuration.defaultTimeout

            val tx = TxAssert.TxContextStackElement(readOnly, transactionIsolation)

            var context = currentCoroutineContext() + tx

            val requestedContext = option?.context
            if (requestedContext != null)
                context += requestedContext

            return withContext(context) {
                suspendTransaction(
                    db = database,
                    transactionIsolation = transactionIsolation,
                    readOnly = readOnly
                ) {
                    withTimeout(timeout) {
                        val res = block(object : ExposedWriter<Db> {
                            override val transaction: Transaction = this@suspendTransaction
                        })

                        // Encourage callers to unwrap Result inside the transaction to avoid leaking laziness.
                        assert(res !is Result<*>) {
                            "Result types must be unboxed before returning from a transaction"
                        }

                        configuration.postCondition?.invoke(this@suspendTransaction, res)

                        res
                    }
                }
            }
        }
    }

    /** Executes a read-only transaction and provides an [ExposedReader] receiver. */
    @TxDsl
    override suspend fun <T> executeRead(option: TxOption?, block: suspend ExposedReader<Db>.() -> T): T =
        execute(readOnly = true, option = option, block)

    /** Executes a write transaction and provides an [ExposedWriter] receiver. Obeys tx-never-nest. */
    @TxDsl
    @TxNever
    override suspend fun <T> executeWrite(option: TxOption?, block: suspend ExposedWriter<Db>.() -> T): T =
        execute(readOnly = false, option = option, block)
}
