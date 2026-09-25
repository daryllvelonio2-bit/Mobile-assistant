plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.shiina.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shiina.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 102
        versionName = "0.102.0"
        ndk {
            // sherpa-onnx ships arm64 + arm32; arm64-only keeps the APK lean (JNY-LX1 is arm64).
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val apkFile = file("${project.layout.buildDirectory.get()}/outputs/apk/debug/app-debug.apk")
        if (apkFile.exists()) {
            val destPath = System.getenv("APK_COPY_DIR") ?: (System.getProperty("user.home") + "/Downloads")
            val destDir = file(destPath)
            if (destDir.exists() || destDir.mkdirs()) {
                apkFile.copyTo(file("${destDir}/shiina-debug.apk"), overwrite = true)
                println("Successfully copied APK to ${destDir}/shiina-debug.apk")
            }
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.savedstate)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.health.connect.client)
    implementation(libs.sceneform)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
