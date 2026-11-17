plugins {
    id("com.android.application")
}

android {
    namespace = "com.hongkilldong.hotspottoggler"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hongkilldong.hotspottoggler"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            // Personal sideload build: sign with the debug key if no release key is configured.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("ttf")
    }

    packaging {
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }
}

dependencies {
    implementation("com.flyfishxu:kadb:2.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.annotation:annotation:1.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
