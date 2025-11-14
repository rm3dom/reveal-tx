import org.gradle.initialization.DependenciesAccessors
import org.gradle.kotlin.dsl.support.serviceOf


plugins {
    `kotlin-dsl`
    java
}


repositories {
    gradlePluginPortal() // so that external plugins can be resolved in dependencies section
    mavenLocal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.atomicfu.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21) // Example: Java 17
    }
}