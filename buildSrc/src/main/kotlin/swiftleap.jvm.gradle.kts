plugins {
    id("swiftleap.common")
    kotlin("jvm")
}

kotlin {
    setDefaults()
}

sourceSets.all {
    kotlin.srcDir("build/generated/kotlin")
}

tasks.test.configure {
    failOnNoDiscoveredTests = false
}

dependencies {
    api(kotlin("stdlib"))
}

setupDetekt()