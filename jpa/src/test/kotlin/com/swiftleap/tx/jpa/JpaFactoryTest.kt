package com.swiftleap.tx.jpa

import com.swiftleap.tx.TxAssert
import com.swiftleap.tx.TxNeverException
import com.swiftleap.tx.TxNeverNestException
import com.swiftleap.tx.TxOption
import jakarta.persistence.EntityManager
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JpaFactoryTest {

    private fun countCities(em: EntityManager): Long =
        em.createQuery("select count(c) from City c", java.lang.Long::class.java)
            .singleResult
            .toLong()

    @Test
    fun `smoke - can insert and read`() = runJpaTest { db ->
        db.executeWrite {
            entityManager.persist(City(name = "London"))
            entityManager.persist(City(name = "Paris"))
        }

        val names = db.executeRead {
            entityManager.createQuery("select c.name from City c order by c.name", String::class.java)
                .resultList
        }
        assertEquals(listOf("London", "Paris"), names)
    }

    @Test
    fun `tx-atom - success commits, failure rolls back`() = runJpaTest { db ->
        // Successful write should persist
        db.executeWrite {
            entityManager.persist(City(name = "One"))
        }
        var count = db.executeRead { countCities(entityManager) }
        assertEquals(1, count)

        // A failing write must roll back entirely
        assertFailsWith<IllegalStateException> {
            db.executeWrite {
                entityManager.persist(City(name = "Two"))
                error("boom")
            }
        }

        // Ensure no partial state leaked
        count = db.executeRead { countCities(entityManager) }
        assertEquals(1, count)
    }

    @Test
    fun `tx-repeat - operation is safe to retry (SQLExceptions then success)`() = runJpaTest { db ->
        var attempts = 0

        // Internal retry policy of JpaFactory should catch SQLException and retry
        db.executeWrite(option = TxOption(isolationLevel = Connection.TRANSACTION_READ_COMMITTED)) {
            attempts++
            entityManager.persist(City(name = "RetryCity"))
            if (attempts < 3) throw SQLException("transient")
        }

        // Only one row should exist after internal retries
        val count = db.executeRead { countCities(entityManager) }
        assertEquals(1, count)
        assertEquals(3, attempts) // 2 failures + 1 success
    }

    @Test
    fun `tx-never - function annotated with TxNever must not run in a transaction`() = runJpaTest { db ->
        @com.swiftleap.tx.TxNever
        suspend fun neverInTx() {
            TxAssert.txNever()
        }

        // Outside a tx is OK
        neverInTx()

        // Inside a tx should throw
        assertFailsWith<TxNeverException> {
            db.executeRead { neverInTx() }
        }
    }

    @Test
    fun `tx-never-nest - disallow illegal nesting patterns`() = runJpaTest { db ->
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
                db.executeRead(option = TxOption(isolationLevel = Connection.TRANSACTION_READ_COMMITTED)) {
                    /* no-op */
                }
            }
        }

        // read -> read is allowed
        db.executeRead { db.executeRead { /* no-op */ } }
    }

    @Test
    fun `tx-read - read transactions set readOnly context and disallow starting writes`() = runJpaTest { db ->
        db.executeRead {
            val top = TxAssert.topTxContextStackElement()
            assertTrue(top != null && top.readOnly)

            // Starting a write from a read tx must be forbidden (covered by tx-never-nest)
            assertFailsWith<TxNeverNestException> { db.executeWrite { /* no-op */ } }
        }
    }

    @Test
    fun `tx-write - write transaction can read and write`() = runJpaTest { db ->
        db.executeWrite {
            entityManager.persist(City(name = "A"))
            // can read within write
            val rows = entityManager.createQuery("select c.name from City c", String::class.java).resultList
            assertEquals(1, rows.size)
        }
        val names = db.executeRead {
            entityManager.createQuery("select c.name from City c", String::class.java).resultList
        }
        assertEquals(listOf("A"), names)
    }
}
