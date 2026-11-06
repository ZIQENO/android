plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.ziqeno.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ziqeno.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.4.9"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    packaging.jniLibs.useLegacyPackaging = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20250517")
    implementation(project(":routing"))
    implementation(files("libs/libXray.aar"))
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("io.grpc:grpc-okhttp:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.re2j:re2j:1.8")
}


