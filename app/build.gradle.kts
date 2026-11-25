plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "io.github.novatv.play"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "io.github.novatv.play"
        minSdk = 25
        targetSdk = 34
        versionCode = 36
        versionName = "1.9.12"
    }

    // Two apps from one source: the Fire TV app, and the phone app with the iPhone's screens.
    flavorDimensions += "form"
    productFlavors {
        create("tv") {
            dimension = "form"
            manifestPlaceholders["mainOrientation"] = "landscape"
        }
        create("phone") {
            dimension = "form"
            applicationIdSuffix = ".phone"
            manifestPlaceholders["mainOrientation"] = "portrait"
            // Firebase (game alerts pushed by the NovaPlay server): its settings read from google-services.json, so
            // the TV app needs no Firebase project of its own.
            val gs = file("src/phone/google-services.json")
            // The value of "name": "..." in the file.
            fun gsValue(name: String) =
                if (gs.exists()) Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(gs.readText())?.groupValues?.get(1).orEmpty() else ""
            buildConfigField("String", "FB_PROJECT", "\"${gsValue("project_id")}\"")
            buildConfigField("String", "FB_SENDER", "\"${gsValue("project_number")}\"")
            buildConfigField("String", "FB_APP", "\"${gsValue("mobilesdk_app_id")}\"")
            buildConfigField("String", "FB_KEY", "\"${gsValue("current_key")}\"")
        }
    }

    buildTypes {
        // Minified so the icon set and libraries stay small; signed with the debug key for sideloading.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Installs the libraries' ahead-of-time compilation profiles, so screens are fast from the first open.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(project(":shared"))
    // The phone app only: game alerts pushed by the NovaPlay server.
    "phoneImplementation"(platform("com.google.firebase:firebase-bom:33.7.0"))
    "phoneImplementation"("com.google.firebase:firebase-messaging")
}
