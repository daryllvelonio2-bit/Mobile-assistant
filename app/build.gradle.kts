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
        versionCode = 74
        versionName = "0.74.0"
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
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val apkFile = file("${project.layout.buildDirectory.get()}/outputs/apk/debug/app-debug.apk")
        if (apkFile.exists()) {
            val destDir = file("/home/janelle/Downloads")
            if (!destDir.exists()) destDir.mkdirs()
            apkFile.copyTo(file("${destDir}/shiina-debug.apk"), overwrite = true)
            println("Successfully copied APK to ${destDir}/shiina-debug.apk")
        }
    }
}

dependencies {
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
}
