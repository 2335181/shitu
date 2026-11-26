// AGP 9 has built-in Kotlin, but its bundled Kotlin Gradle plugin is older than the
// Kotlin 2.4 compiler this project uses. Putting KGP on the root buildscript classpath
// makes built-in Kotlin use 2.4.21 instead of AGP's bundled version.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
