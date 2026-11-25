// NovaPlay without a screen, for the NovaPlay server: the apps' own code loads the playlist, the guide and the addons,
// works out each game's channels and keeps the library ready for the apps to download. One jar (novaplay-server.jar).
plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okio:okio:3.12.0")
}

val serverJar by tasks.registering(Jar::class) {
    group = "build"
    description = "The NovaPlay server's program, with everything it needs, in one jar."
    archiveFileName.set("novaplay-server.jar")
    manifest { attributes["Main-Class"] = "io.github.novatv.play.headless.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            // Screens' native drawing libraries and the icon set: never used without a screen, and most of the size.
            .filterNot { f -> listOf("skiko-awt-runtime", "material-icons-extended").any { it in f.name } }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}
