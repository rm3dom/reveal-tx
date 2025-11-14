plugins {
    id("swiftleap.jvm")
}



dependencies {
    api(project(":tx"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    testImplementation(libs.h2database.h2)

    // Use the Kotlin JUnit 5 integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
