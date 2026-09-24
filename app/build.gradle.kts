plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eugene.golftrace"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eugene.golftrace"
        manifestPlaceholders["appLabel"] = "杆头轨迹"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        debug {
            // 与已安装的正式版共存，避免不同电脑的 debug 签名无法覆盖安装。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "杆头轨迹 Debug"
        }
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
