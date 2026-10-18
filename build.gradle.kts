// Upgrade AGP's built-in Kotlin together with the Compose compiler plugin.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
