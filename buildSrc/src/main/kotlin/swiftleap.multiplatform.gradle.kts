plugins {
    id("swiftleap.common")
    kotlin("multiplatform")
}

val development: String by project


kotlin {
    setDefaults()
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlin("stdlib"))
            }
        }

        all {
            kotlin.srcDir("build/generated/kotlin")
        }
    }
}

setupDetekt()


