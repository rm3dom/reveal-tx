[![Main](https://github.com/rm3dom/reveal-tx/actions/workflows/main.yml/badge.svg)](https://github.com/rm3dom/reveal-tx/actions/workflows/main.yml)

# reveal-tx?

A small library to make using transactions safer and explicit; essentially make transaction boundaries
blatantly obvious and offer some compile time guarantees. Small enough to just copy the code, you do not need to add a
dependency if you do not want to.

It aims to make the following questions easy to answer with confidence:

* Do our operations automatically retry on transient failures? Did you test for it?
* If we retry, will it still succeed, or did we leave behind state that breaks retries?
* Are we wrapping code in transactions that should not run inside a transaction?
* Have we stress‑tested this behavior, and can we offer clear guarantees (instead of just surfacing errors to users)?

Scope: this does not attempt to provide distributed guarantees. We still need solid database and query design.
Databases give us atomic guarantees; our code should also offer guarantees, so it's easier to reason about.

There is also a C# example [here](https://github.com/rm3dom/ColourYourFunctions/tree/main/DotNet).

## Important note

You can get most of the same guarantees by testing transaction failures and retries in your existing code.
*Deadlocks and other database errors will happen in production*, if you did not test for them, one of the following
could happen:

* You have partially written data (wrote to a queue or called an API).
* You have left behind state that breaks retries (shared mutable state).

## Atomicity refresher

Atomicity is a core database concept: a transaction either completes entirely or not at all. If anything fails, the
whole transaction is rolled back as if it never happened.

We want our application code to maintain those properties and be retryable. Because it’s hard to spot logic that breaks
these guarantees, we should lean on the compiler and tests where possible.

## Guarantees

The goal is to provide the guarantees below. Prefer compile‑time guarantees over run‑time ones. The short codes can be
referenced in examples and comments.

* tx-atom
* tx-repeat
* tx-never
* tx-never-nest
* tx-read
* tx-write

### tx-atom

A group of database operations (a transaction) is atomic. This is the strongest guarantee and is provided by the
database.

### tx-repeat

The operations within a transaction are safe to retry.

On a deadlock or connection error, the transaction can be retried safely. All operations within the transaction scope
are either discarded on rollback or idempotent (preferably discarded rather than relying on idempotency).

### tx-never

No transaction may be active in the call stack.

Some functions must never run within a transaction. For example, long‑running operations should not execute inside a
transaction. Likewise, a function that starts its own write transaction must not be invoked from inside another
transaction (see `tx-never-nest`).

### tx-never-nest

Only one transaction may exist in the call stack.

* You may not nest write transactions.
* You may not nest a transaction when the outer transaction is a write and the inner transaction uses `ReadCommitted`
  isolation level or higher.

Note: this refers to starting a new transaction within the scope of another, not to database‑managed nested
transactions.

### tx-read

Read‑only across the call stack.

Every function participating in a read transaction must be read‑only, including all calls up the stack. Function
signatures should make read‑only intent clear for themselves and their callees.

### tx-write

Read/write across the call stack.

Functions participating in a write transaction may read or write.

## Enforcement

| Guarantee     | Enforcement                                    |
|---------------|------------------------------------------------|
| tx-atom       | Run‑time assertion / Testing                   |
| tx-repeat     | Run‑time assertion / Testing                   |
| tx-never      | Run‑time assertion / Testing / Compiler plugin |
| tx-never-nest | Run‑time assertion / Testing / Compiler plugin |
| tx-read       | Compile‑time                                   |
| tx-write      | Compile‑time                                   |

Some compile‑time guarantees come from “colouring” functions with their transaction intent/participation. Repeatability
is verified by forcing retries at run time (see Testing). Most guarantees—except `tx-repeat` and `tx-atom`—could be
enforced by a compiler plugin.

## Colouring

It’s only “colouring” if your function can run without a transaction. A function cannot guarantee atomicity without
a transaction—so that is not colouring. It also cannot write to the database without a connection—so that is not colouring.


## Example

```kotlin
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

    //Enable chaos 👿
    chaos {
        enabled = true
        seed = 42
    }

    //tx-repeat, use chaos to test for retries.
    //Fail 25% of the time forcing a transaction retry.
    val chaosKey = ChaosKey("test", ChaosProfile.FAIL_25)

    val txConfiguration = TxConfiguration(postCondition = { _, _ ->
        //tx-repeat, inject chaos 👿
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

    val townsDb = Database.connect(
        url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        driver = "org.h2.Driver",
        user = "sa",
        password = ""
    )

    transaction(townsDb) {
        SchemaUtils.create(Towns)
    }


    val citiesAndTowns = citiesAndTowns(
        citiesDb = CitiesDb(citiesDb, txConfiguration),
        townsDb = TownsDb(townsDb, txConfiguration)
    )

    println(citiesAndTowns)
}
```

# Disclaimer

This code is not production ready. Use at your own risk; I do.