plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dev.lerist.fakelocation.repro.runtime"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
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
    implementation("androidx.core:core-ktx:1.13.1")
}
