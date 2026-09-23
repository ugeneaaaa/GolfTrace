plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eugene.golftrace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eugene.golftrace"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android.testOptions.unitTests.isReturnDefaultValues = true
tasks.withType<Test>().configureEach { maxHeapSize = "2g"; testLogging { showStandardStreams = true }; outputs.upToDateWhen { false } }
