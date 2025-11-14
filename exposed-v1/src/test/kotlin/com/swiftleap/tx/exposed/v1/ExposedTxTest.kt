package com.swiftleap.tx.exposed.v1

import com.swiftleap.tx.TxAssert
import com.swiftleap.tx.TxNeverException
import com.swiftleap.tx.TxNeverNestException
import com.swiftleap.tx.chaos.RetryPolicy
import com.swiftleap.tx.chaos.retrySuspending
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith


class ExposedTxTest {

    @Test
    fun `can read and write`() = runH2Test { db ->
        db.executeWrite {
            val _ = Cities.insert {
                it[name] = "St. Petersburg"
            } get Cities.id

            val _ = Cities.insert {
                it[name] = "Munich"
            } get Cities.id
        }
        val cities = db.executeRead {
            Cities.select(Cities.name).toList()
        }
        assertEquals(2, cities.size)
    }

    @Test
    fun `tx-atom - success commits, failure rolls back`() = runH2Test { db ->
        // Successful write should persist
        db.executeWrite {
            val _ = Cities.insert { it[name] = "One" } get Cities.id
        }
        var count = db.executeRead { Cities.select(Cities.id).count() }
        assertEquals(1, count)

        // A failing write must roll back entirely
        assertFailsWith<IllegalStateException> {
            db.executeWrite {
                val _ = Cities.insert { it[name] = "Two" } get Cities.id
                error("boom")
            }
        }
        // Ensure no partial state leaked
        count = db.executeRead { Cities.select(Cities.id).count() }
        assertEquals(1, count)
    }

    @Test
    fun `tx-repeat - operation is safe to retry (forced failures then success)`() = runH2Test { db ->
        class RetryMe : RuntimeException()

        var attempts = 0

        // Force two rollbacks and then allow success
        retrySuspending(
            policy = RetryPolicy(retryableExceptions = setOf(RetryMe::class), maxAttempts = 5)
        ) {
            db.executeWrite(option = com.swiftleap.tx.TxOption(isolationLevel = Connection.TRANSACTION_READ_COMMITTED)) {
                attempts++
                val _ = Cities.insert { it[name] = "RetryCity" } get Cities.id
                if (attempts < 3) throw RetryMe()
            }
        }

        // Only one row should exist after retries
        val count = db.executeRead { Cities.select(Cities.id).count() }
        assertEquals(1, count)
        assertEquals(3, attempts) // 2 failures + 1 success
    }

    @Test
    fun `tx-never - function annotated with TxNever must not run in a transaction`() = runH2Test { db ->
        @com.swiftleap.tx.TxNever
        suspend fun neverInTx() { // runtime guard
            TxAssert.txNever()
        }

        // Outside a tx is OK
        neverInTx()

        // Inside a tx should throw
        assertFailsWith<TxNeverException> {
            db.executeRead {
                neverInTx()
            }
        }
    }

    @Test
    fun `tx-never-nest - disallow illegal nesting patterns`() = runH2Test { db ->
        // write -> write is forbidden
        assertFailsWith<TxNeverNestException> {
            db.executeWrite {
                db.executeWrite { /* no-op */ }
            }
        }

        // read -> write is forbidden
        assertFailsWith<TxNeverNestException> {
            db.executeRead {
                db.executeWrite { /* no-op */ }
            }
        }

        // write -> read (READ_COMMITTED) is forbidden per rules
        assertFailsWith<TxNeverNestException> {
            db.executeWrite {
                db.executeRead(option = com.swiftleap.tx.TxOption(isolationLevel = Connection.TRANSACTION_READ_COMMITTED)) {
                    /* no-op */
                }
            }
        }

        // read -> read is allowed
        db.executeRead {
            db.executeRead { /* no-op */ }
        }
    }

    @Test
    fun `tx-read - read transactions set readOnly context and disallow starting writes`() = runH2Test { db ->
        db.executeRead {
            val top = TxAssert.topTxContextStackElement()
            // Ensure the logical tx context is marked read-only
            kotlin.test.assertTrue(top != null && top.readOnly)

            // Starting a write from a read tx must be forbidden (covered by tx-never-nest)
            assertFailsWith<TxNeverNestException> {
                db.executeWrite { /* no-op */ }
            }
        }
    }

    @Test
    fun `tx-write - write transaction can read and write`() = runH2Test { db ->
        db.executeWrite {
            val _ = Cities.insert { it[name] = "A" } get Cities.id
            // can read within write
            val rows = Cities.select(Cities.name).toList()
            assertEquals(1, rows.size)
        }
        val names = db.executeRead { Cities.select(Cities.name).map { it[Cities.name] } }
        assertEquals(listOf("A"), names)
    }
}