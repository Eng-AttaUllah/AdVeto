// Top-level build file: plugin versions shared by all modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // KSP must match the Kotlin version exactly: Kotlin 2.0.21 -> KSP 2.0.21-1.0.28
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
