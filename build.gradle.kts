buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kadb 2.1.5 is built with Kotlin 2.4 metadata; AGP 9's built-in Kotlin is older.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
