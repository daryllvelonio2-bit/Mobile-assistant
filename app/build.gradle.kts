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
        versionCode = 114
        versionName = "0.110.0"
    }

    buildTypes {
        debug {
            ndk {
                // Debug ships arm64 + x86_64: the fleet Waydroid image is x86_64
                // (ro.product.cpu.abi=x86_64), so an arm64-only debug APK cannot be installed
                // and no on-device verification can run. sherpa-onnx-1.13.8.aar ships x86_64
                // native libs (jni/x86_64/libonnxruntime.so etc.), so this is ABI-complete.
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            // Release stays arm64-only: the target handset (JNY-LX1) is arm64 and it keeps
            // the shipped APK lean. Do NOT add x86_64 here.
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
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
    implementation("androidx.compose.material:material-icons-extended")

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
