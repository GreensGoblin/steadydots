plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.co.steadydots"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.steadydots"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed signing key so each new build installs over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("steadydots.keystore")
            storePassword = "steadydots"
            keyAlias = "steadydots"
            keyPassword = "steadydots"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
