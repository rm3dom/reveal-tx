package com.swiftleap.tx.exposed.v1

import org.jetbrains.exposed.v1.core.Table

object Cities : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id)
}
