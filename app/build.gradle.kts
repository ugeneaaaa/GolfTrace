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
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "com.eugene.golftrace.ExposureProbe"
    }

    buildTypes {
        // 本机调试与日常使用统一 applicationId，安装时升级同一应用。
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
