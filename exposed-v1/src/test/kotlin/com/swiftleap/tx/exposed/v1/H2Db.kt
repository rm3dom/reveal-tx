package com.swiftleap.tx.exposed.v1

import kotlinx.coroutines.test.runTest
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class H2Db(
    database: Database,
    configuration: TxConfiguration = TxConfiguration()
) : ExposedFactory<H2Db>(database, configuration)

fun runH2Test(block: suspend (H2Db) -> Unit) = runTest {
    val ds = JdbcDataSource()
    ds.setURL("jdbc:h2:mem:test")
    ds.user = "sa"
    ds.password = ""

    //Keep the db alive for the test
    ds.connection.use {
        val db = Database.connect(ds)
        transaction(db) {
            SchemaUtils.create(Cities)
        }

        val h2Db = H2Db(db)
        block(h2Db)
    }
}
