plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.lerist.fakelocation.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.lerist.fakelocation.repro"
        minSdk = 21
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation(project(":core-model"))
    implementation(project(":core-ipc"))
    implementation(project(":core-runtime"))
    implementation(project(":core-hookbridge"))
    implementation(project(":injector-orchestrator"))
    implementation(project(":payload-shared"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
