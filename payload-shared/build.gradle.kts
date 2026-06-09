plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dev.lerist.fakelocation.repro.payload"
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
    implementation(project(":core-ipc"))
    implementation(project(":core-runtime"))
    implementation(project(":core-hookbridge"))
    implementation(project(":injector-orchestrator"))
    implementation("androidx.core:core-ktx:1.13.1")
}
