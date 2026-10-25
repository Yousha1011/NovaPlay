plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

/** iOS builds need Xcode, so the iPhone targets only exist on a Mac (the cloud build). */
val onMac = System.getProperty("os.name").startsWith("Mac")

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    if (onMac) listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }

    sourceSets {
        // Android and Windows both run on the JVM: what they share lives here.
        val jvmShared by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmShared)
        // Windows and iPhone: mouse and touch versions of the TV widgets.
        val screenMain by creating { dependsOn(commonMain.get()) }
        if (onMac) {
            // The default hierarchy is off (the custom sets above), so iOS is wired by hand.
            val ios = maybeCreate("iosMain")
            ios.dependsOn(screenMain)
            getByName("iosArm64Main").dependsOn(ios)
            getByName("iosSimulatorArm64Main").dependsOn(ios)
            ios.dependencies { implementation("io.ktor:ktor-client-darwin:3.1.3") }
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            implementation("com.squareup.okio:okio:3.12.0")
            implementation("io.ktor:ktor-client-core:3.1.3")
            implementation("androidx.sqlite:sqlite:2.5.2")
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")
            implementation("io.coil-kt.coil3:coil-compose:3.2.0")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.3")
            implementation("androidx.tv:tv-material:1.0.0")
            implementation("androidx.activity:activity-compose:1.9.3")
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependsOn(screenMain)
            dependencies {
                implementation(compose.desktop.common)
                implementation("io.ktor:ktor-client-okhttp:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
    }
}

android {
    namespace = "io.github.novatv.play.shared"
    compileSdk = 35
    defaultConfig { minSdk = 25 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.novatv.play.res"
}
