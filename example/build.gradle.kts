import dev.detekt.gradle.Detekt
import org.gradle.kotlin.dsl.withType

plugins {
    id("swiftleap.jvm")
    application
}



dependencies {
    api(project(":exposed-v1"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.h2database.h2)
}

tasks.withType<Detekt>().configureEach {
    enabled = false
}