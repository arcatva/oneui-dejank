plugins {
    id("com.android.application") version "9.5.1" apply false
    // Must match the Kotlin Gradle plugin that AGP's built-in Kotlin resolves to.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
