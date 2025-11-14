package com.swiftleap.tx.exposed

import com.swiftleap.tx.TxAssert
import com.swiftleap.tx.TxNever
import com.swiftleap.tx.TxReader
import com.swiftleap.tx.TxWriter
import com.swiftleap.tx.chaos.ChaosKey
import com.swiftleap.tx.chaos.ChaosProfile
import com.swiftleap.tx.chaos.injectChaos
import com.swiftleap.tx.exposed.v1.ExposedFactory
import com.swiftleap.tx.exposed.v1.TxConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException

/**
 * A typed database / tx factory for cities.
 */
class CitiesDb(
    database: Database,
    configuration: TxConfiguration = TxConfiguration()
) : ExposedFactory<CitiesDb>(database, configuration)

/**
 * A typed database / tx factory for towns.
 */
class TownsDb(
    database: Database,
    configuration: TxConfiguration = TxConfiguration()
) : ExposedFactory<TownsDb>(database, configuration)


object Cities : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id)
}

object Towns : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id)
}

/**
 * tx-never, this should never be called in a transaction.
 * It is long-running and non-idempotent (can't be retried).
 */
@TxNever
suspend fun apiCall() {
    TxAssert.txNever()
    // do API stuff
    delay(1000L)
}

/**
 * tx-write, this cannot be called in a read CitiesDb transaction.
 */
context(_: TxWriter<CitiesDb>)
fun createCity(name: String) {
    val _ = Cities.insert {
        it[Cities.name] = name
    } get Cities.id
}

/**
 * tx-read, this can be called in read/write CitiesDb transactions.
 */
context(_: TxReader<CitiesDb>)
fun readCities(): List<String> = Cities.select(Cities.name).map { it[Cities.name] }.toList()

/**
 * tx-write, this cannot be called in a read TownsDb transaction.
 */
context(_: TxWriter<TownsDb>)
fun createTown(name: String) {
    val _ = Towns.insert {
        it[Towns.name] = name
    } get Towns.id
}

/**
 * tx-read, this can be called in read/write TownsDb transactions.
 */
context(_: TxReader<TownsDb>)
fun readTowns(): List<String> = Towns.select(Towns.name).map { it[Towns.name] }.toList()


suspend fun citiesAndTowns(citiesDb: CitiesDb, townsDb: TownsDb): List<String> {
    //NOTE: You should use the saga pattern around the two write transactions.

    val towns = townsDb.executeWrite {
        createTown("B")
        readTowns()
    }

    //tx-never, never call in a transaction
    //If called in a transaction, it will throw a never-nest exception.
    //This may be a long-running or non-idempotent operation.
    apiCall()

    val cities = citiesDb.executeWrite {
        createCity("A")
        readCities()
    }

    return cities + towns
}

fun main(): Unit = runBlocking {

    //tx-repeat, use chaos to test for retries.
    //Fail 25% of the time forcing a transaction retry.
    val chaosKey = ChaosKey("test", ChaosProfile.FAIL_25)

    val txConfiguration = TxConfiguration(postCondition = { _, _ ->
        //tx-repeat, inject chaos
        injectChaos(chaosKey) {
            throw SQLException(it)
        }
    })

    val citiesDb = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        user = "sa",
        password = ""
    )

    transaction(citiesDb) {
        SchemaUtils.create(Cities)
    }

    val towns2 = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        user = "sa",
        password = ""
    )

    transaction(towns2) {
        SchemaUtils.create(Towns)
    }


    val citiesAndTowns = citiesAndTowns(
        citiesDb = CitiesDb(citiesDb, txConfiguration),
        townsDb = TownsDb(towns2, txConfiguration)
    )

    println(citiesAndTowns)
}