/**
 * All the ugly versions in one place. God help us all.
 */

import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.*

private val kVersion = KotlinVersion.KOTLIN_2_2
private val jVersion = 21
private val jTarget = JvmTarget.JVM_21

fun KotlinCommonCompilerOptions.setKotlinDefaults() {
    verbose.set(true)
    languageVersion.set(kVersion)
    apiVersion.set(kVersion)
    allWarningsAsErrors.set(true)
    freeCompilerArgs.addAll(
        "-Xcontext-parameters",
        "-Xexpect-actual-classes",
        "-Xreturn-value-checker=full",
        //This is dangerous; it removes statements thinking they unused:
        //"-XXLanguage:+UnnamedLocalVariables",
        "-Werror"
    )
}

fun KotlinJvmCompilerOptions.setJvmDefaults() {
    setKotlinDefaults()
    jvmTarget.set(jTarget)
    freeCompilerArgs.addAll(
        "-Xjdk-release=$jVersion",
    )
}

fun JavaCompile.setDefaults() {
    options.release.set(jVersion)
}

fun KotlinJvmProjectExtension.setDefaults() {
    compilerOptions {
        setJvmDefaults()
    }
}

fun KotlinMultiplatformExtension.setDefaults() {
    jvm {
        compilerOptions {
            setJvmDefaults()
        }
    }
    compilerOptions {
        setKotlinDefaults()
    }
}