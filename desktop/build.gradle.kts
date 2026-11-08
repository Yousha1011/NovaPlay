import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // VLC's engine: plays the live TS, HLS and film streams IPTV panels send.
    implementation("uk.co.caprica:vlcj:4.8.3")
}

compose.desktop {
    application {
        mainClass = "io.github.novatv.play.desktop.MainKt"
        // A big playlist needs about 150 MB of heap; 1 GB leaves room. Java keeps the heap it has grown to unless told:
        // these give unused memory back to Windows within a minute, and share repeated texts.
        // -Xms: Java otherwise starts at, and never shrinks below, 1/64 of the PC's memory (500 MB on 32 GB).
        jvmArgs += listOf("-Xms64m", "-Xmx1g", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=60000", "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25", "-XX:+UseStringDeduplication", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "NovaPlay"
            packageVersion = "1.6.2"
            description = "NovaPlay IPTV player"
            vendor = "NovaPlay"
            copyright = "NovaPlay"
            // libvlc and its plugins, shipped beside the app (resources/windows/vlc).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.sql", "jdk.unsupported", "java.naming", "java.management")
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                menuGroup = "NovaPlay"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed, so a newer installer upgrades the installed app in place.
                upgradeUuid = "6f1d2a7e-3c4b-4e8a-9a51-2b7c0d4e9f13"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
