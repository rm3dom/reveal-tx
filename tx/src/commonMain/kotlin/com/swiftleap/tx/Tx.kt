/**
 * Compile‑time friendly primitives for structuring and enforcing database transaction usage.
 *
 * This module provides a small set of types and assertions to help you:
 * - Colour functions by their transaction role (tx-read / tx-write / tx-never)
 * - Prevent accidental nesting of transactions (tx-never-nest)
 * - Communicate intent in APIs while leaving storage details to integrations (JPA, Exposed, etc.)
 *
 * Key ideas (see README for definitions):
 * - tx-read: read‑only across the call stack
 * - tx-write: read/write across the call stack
 * - tx-never: must not be called when a transaction is active
 * - tx-never-nest: do not start a new transaction inside another
 */

@file:MustUseReturnValue
@file:Suppress("RUNTIME_ANNOTATION_NOT_SUPPORTED")

package com.swiftleap.tx

import kotlinx.coroutines.currentCoroutineContext
import java.sql.Connection
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * DSL marker.
 */
@DslMarker
annotation class TxDsl

/** Base type for all transaction‑related runtime exceptions in this module. */
open class TxException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Thrown by [TxAssert.txNever] when a function annotated with [TxNever] runs inside a transaction. */
class TxNeverException(message: String = "tx-never: do not call in a transaction", cause: Throwable? = null) :
    TxException(message, cause)

/** Thrown when attempting to start a nested transaction that violates tx-never-nest rules. */
class TxNeverNestException(message: String, cause: Throwable? = null) : TxException(message, cause)


/**
 * Annotation for functions that must never be invoked while a transaction is active.
 *
 * Use [TxAssert.txNever] at the start of such functions to perform the run‑time check.
 *
 * Example:
 * ```kotlin
 * @TxNever
 * fun doNotParticipateInTx() {
 *   // Fails fast if called from within a transaction
 *   TxAssert.txNever()
 *   // do something long‑running or non‑transactional
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class TxNever


/**
 * Marker interface for a participant that only performs read operations within a transaction.
 *
 * Guarantees (by convention and/or compile‑time colouring in your codebase):
 * - tx-read
 * - tx-never-nest (a function participating in a read tx must not start a write tx)
 */
interface TxReader<Db>

/**
 * Marker interface for a participant that may perform writes within a transaction.
 *
 * Implies:
 * - tx-write (and therefore tx-read)
 * - tx-never-nest
 */
interface TxWriter<Db> : TxReader<Db>


/**
 * Options passed when creating a transaction.
 *
 * @property timeout Optional timeout for the transaction, if supported by the integration.
 * @property isolationLevel Database isolation level (e.g. [Connection.TRANSACTION_READ_COMMITTED]).
 * @property context Optional coroutine context to run the transaction in (e.g. dispatchers).
 */
@JvmRecord
data class TxOption(
    val timeout: Duration? = null,
    val isolationLevel: Int? = null,
    val context: CoroutineContext? = null,
)


/**
 * Factory capable of starting read transactions.
 * Concrete integrations (JPA, Exposed) implement this to provide their own [Reader].
 */
interface TxReadFactory<Reader> {
    /**
     * Starts a new read‑only transaction and runs [block] with a [Reader] receiver.
     * Implementations should assert tx-never-nest appropriately.
     */
    @TxDsl
    suspend fun <T> executeRead(option: TxOption? = null, block: suspend Reader.() -> T): T
}


/**
 * Factory capable of starting write transactions (also supports reads via [TxReadFactory]).
 */
@TxDsl
interface TxFactory<Reader, Writer> : TxReadFactory<Reader> where Writer : Reader {

    /**
     * Starts a new write transaction and runs [block] with a [Writer] receiver.
     *
     * Nesting rules (tx-never-nest):
     * - write -> write is forbidden
     * - write -> READ_COMMITTED (or higher) is forbidden when the outer is write
     * - read  -> write is forbidden
     *
     * Notes:
     * - If database‑managed nested transactions are desired, they can be added in a future
     *   iteration without changing the API.
     */
    @TxNever
    @TxDsl
    suspend fun <T> executeWrite(option: TxOption? = null, block: suspend Writer.() -> T): T
}


/** Assertions and coroutine‑context plumbing to enforce tx‑related guarantees at run time. */
object TxAssert {
    /** Describes one frame on the logical transaction stack.
     *
     * @property readOnly Whether the transaction is readonly.
     * @property transactionIsolation The isolation level of the transaction.
     */
    @JvmRecord
    data class TxContextStackElement(
        val readOnly: Boolean,
        val transactionIsolation: Int,
    )

    /**
     * CoroutineContext element storing a persistent (immutable) stack of transaction frames.
     * Implementations push/pop using [plus] while executing a transaction block.
     */
    @JvmRecord
    private data class TxContext(
        val txStack: ReadOnlyStack<TxContextStackElement> = ReadOnlyStack(),
        override val key: CoroutineContext.Key<*> = Key
    ) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<TxContext>
    }

    /**
     * Pushes a new [TxContextStackElement] onto the current coroutine context stack.
     * Typical usage inside an implementation:
     * ```kotlin
     * withContext(coroutineContext + TxContextStackElement(readOnly = true, transactionIsolation)) {
     *   // run transactional block
     * }
     * ```
     */
    operator fun CoroutineContext.plus(stackElement: TxContextStackElement): CoroutineContext {
        val currentTxContext = this[TxContext] ?: TxContext()
        return this + currentTxContext.copy(txStack = currentTxContext.txStack.push(stackElement))
    }

    /** Returns the top frame of the transaction stack for the current coroutine, if any. */
    suspend fun topTxContextStackElement(): TxContextStackElement? =
        currentCoroutineContext()[TxContext]?.txStack?.peek()

    /**
     * Enforces tx-never-nest rules given the requested [readOnly] flag and [transactionIsolation].
     *
     * When a transaction is already active (top of stack exists):
     * - write -> write is forbidden
     * - write -> READ_COMMITTED (or higher) is forbidden when the outer is write
     * - read  -> write is forbidden
     *
     * Isolation level comparison uses [Connection.TRANSACTION_READ_UNCOMMITTED] as the lowest level.
     * Implementations should call this before starting a new transaction.
     */
    @Suppress("ThrowsCount")
    suspend inline fun txNeverNest(readOnly: Boolean, transactionIsolation: Int) {
        val top = topTxContextStackElement() ?: return

        //tx-never-nest write -> write
        if (!readOnly && !top.readOnly) {
            throw TxNeverNestException(
                "tx-never-nest write -> write: " +
                        "nested write transactions is not allowed"
            )
        }

        //tx-never-nest write -> ReadCommitted
        if (!readOnly && transactionIsolation > Connection.TRANSACTION_READ_UNCOMMITTED) {
            throw TxNeverNestException(
                "tx-never-nest write -> READ_COMMITTED: " +
                        "nested READ_COMMITTED transactions is not allowed"
            )
        }

        //tx-never-nest read -> write
        if (readOnly && !top.readOnly) {
            throw TxNeverNestException("tx-never-nest read -> write")
        }
    }

    /**
     * Asserts that no transaction is active; otherwise throws [TxNeverException].
     *
     * Useful at the beginning of functions annotated with [TxNever].
     */
    @TxDsl
    suspend inline fun txNever() {
        val _ = topTxContextStackElement() ?: return
        throw TxNeverException("tx-never: do not call in a transaction")
    }
}

