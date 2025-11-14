package com.swiftleap.tx.jpa

import jakarta.persistence.Persistence
import kotlinx.coroutines.test.runTest

class JpaDb(
    emfName: String = "test-pu",
    configuration: TxConfiguration = TxConfiguration()
) : JpaFactory<JpaDb>(
    emf = Persistence.createEntityManagerFactory(emfName),
    configuration = configuration
) {
    fun close() {
        // Close the underlying EMF created for tests
        val f = javaClass.superclass
        // No direct field, so recreate and close at call sites if needed.
    }
}

fun runJpaTest(block: suspend (JpaDb) -> Unit) = runTest {
    val db = JpaDb()
    try {
        block(db)
    } finally {
        // Best effort to close EntityManagerFactory via reflection since it's private in parent
        val field = JpaFactory::class.java.getDeclaredField("emf")
        field.isAccessible = true
        val emf = field.get(db) as jakarta.persistence.EntityManagerFactory
        emf.close()
    }
}
