plugins {
    id("swiftleap.jvm")
}

dependencies {
    api(project(":tx"))
    // Coroutines support for suspend APIs
    implementation(libs.kotlinx.coroutines.core)
    // JPA API (Jakarta)
    implementation("jakarta.persistence:jakarta.persistence-api:3.2.0")

    // JPA provider and in-memory DB for tests
    testImplementation("org.hibernate.orm:hibernate-core:7.1.7.Final")
    testImplementation(libs.h2database.h2)
    testImplementation(libs.kotlinx.coroutines.test)

    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
