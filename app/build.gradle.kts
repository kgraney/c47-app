plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kevingraney.c47"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kevingraney.c47"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    // Release signing. Reads keystore path + credentials from gradle
    // properties so secrets stay out of git (put them in
    // ~/.gradle/gradle.properties or pass via -P flags). When the keystore
    // property isn't set, release builds fall back to the debug signing
    // config so `assembleRelease` / `bundleRelease` still work locally —
    // just not suitable for Play upload.
    val releaseStoreFile = (project.findProperty("C47_RELEASE_STORE_FILE") as String?)?.let { file(it) }
    val hasReleaseKeystore = releaseStoreFile?.exists() == true
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = project.findProperty("C47_RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("C47_RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("C47_RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
