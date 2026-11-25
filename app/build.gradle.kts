import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.elistratov.dictofon"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.elistratov.dictofon"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Официальный sherpa-onnx AAR (Kotlin API + JNI) скачивается автоматически при сборке.
val sherpaAar = file("libs/sherpa-onnx.aar")
val downloadSherpa by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.3/sherpa-onnx-2.0.3.aar"
            println("Скачиваю $url")
            URI(url).toURL().openStream().use { input ->
                sherpaAar.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadSherpa) }

dependencies {
    implementation(files("libs/sherpa-onnx.aar"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
