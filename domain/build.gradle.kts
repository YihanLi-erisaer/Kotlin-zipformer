plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Coroutines for UseCases - core is platform-independent (no Android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
