plugins {
    id("com.android.application")
    kotlin("android")
}

val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse(providers.environmentVariable("ANDROID_HOME"))
val nativeRuntimeSourceDir = rootProject.layout.projectDirectory.dir("native-runtime")
val nativeRuntimeBuildRoot = layout.buildDirectory.dir("native-runtime")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/assets/nativeRuntime")

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

    sourceSets["main"].assets.srcDir(generatedRuntimeAssets)

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

val compileNativeRuntime by tasks.registering {
    val nativeBuildDir = nativeRuntimeBuildRoot.map { it.dir("arm64-v8a-release") }
    val outputDir = generatedRuntimeAssets.map { it.dir("runtime") }

    inputs.dir(nativeRuntimeSourceDir)
    outputs.dir(outputDir)

    doLast {
        val sdk = sdkRoot.orNull ?: error("ANDROID_SDK_ROOT or ANDROID_HOME must be set")
        val ndkDir = file("$sdk/ndk")
        val ndkRoot = ndkDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
            ?: error("No Android NDK found under ${ndkDir.absolutePath}")
        val cmakeBin = file("$sdk/cmake/3.22.1/bin/cmake.exe")
        val ninjaBin = file("$sdk/cmake/3.22.1/bin/ninja.exe")
        if (!cmakeBin.exists()) {
            error("Missing CMake at ${cmakeBin.absolutePath}")
        }
        if (!ninjaBin.exists()) {
            error("Missing Ninja at ${ninjaBin.absolutePath}")
        }

        val buildDirFile = nativeBuildDir.get().asFile
        val outputDirFile = outputDir.get().asFile
        buildDirFile.mkdirs()
        outputDirFile.mkdirs()

        exec {
            commandLine(
                cmakeBin.absolutePath,
                "-S", nativeRuntimeSourceDir.asFile.absolutePath,
                "-B", buildDirFile.absolutePath,
                "-G", "Ninja",
                "-DANDROID_ABI=arm64-v8a",
                "-DANDROID_PLATFORM=android-21",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DANDROID_NDK=${ndkRoot.absolutePath}",
                "-DCMAKE_TOOLCHAIN_FILE=${ndkRoot.absolutePath}/build/cmake/android.toolchain.cmake",
                "-DCMAKE_MAKE_PROGRAM=${ninjaBin.absolutePath}",
            )
        }
        exec {
            commandLine(
                cmakeBin.absolutePath,
                "--build", buildDirFile.absolutePath,
                "--target", "inj64", "fl_init64", "fl_app64", "lh64",
            )
        }

        copy {
            from(File(buildDirFile, "inj64"))
            into(outputDir.get().dir("bin").asFile)
            rename { "inj64" }
        }
        copy {
            from(File(buildDirFile, "libfl_init64.so"))
            into(outputDir.get().dir("native").asFile)
        }
        copy {
            from(File(buildDirFile, "libfl_app64.so"))
            into(outputDir.get().dir("native").asFile)
        }
        copy {
            from(File(buildDirFile, "liblh64.so"))
            into(outputDir.get().dir("native").asFile)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(compileNativeRuntime)
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
