import dev.detekt.gradle.Detekt

plugins {
    id("org.jetbrains.kotlinx.atomicfu")
    id("dev.detekt")
}

val versionCatalog = the<VersionCatalogsExtension>().named("libs")

repositories {
    //google()
    mavenCentral()
    mavenLocal()
    maven("https://packages.jetbrains.team/maven/p/kds/kotlin-ds-maven")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
}

tasks.withType<JavaCompile> {
    setDefaults()
}



tasks.withType<Detekt>().configureEach {
    enabled = false
    //Use setupDetekt()
}
