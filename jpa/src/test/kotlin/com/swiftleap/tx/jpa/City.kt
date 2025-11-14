package com.swiftleap.tx.jpa

import jakarta.persistence.*

@Entity
@Table(name = "cities")
class City(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, length = 50)
    var name: String = ""
)
