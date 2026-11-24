plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":api"))
    implementation(project(":runtime-js"))
    implementation(project(":runtime-jar"))
    implementation(project(":runtime-py"))
    implementation(libs.ktor.server.cio)
    // mDNS announcement while LAN sharing is on (desktop / NAS only; the Android app never shares).
    implementation(libs.jmdns)
    runtimeOnly(libs.logback)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val demoHostClasses by tasks.registering(JavaCompile::class) {
    source = fileTree("demo-jar/src") { include("**/*.java") }
    classpath = configurations.compileClasspath.get()
    destinationDirectory = layout.buildDirectory.dir("demo-host-classes")
    options.release = 11
    options.encoding = "UTF-8"
}

val demoHostArchive by tasks.registering(Jar::class) {
    archiveFileName = "spider-host.jar"
    from(demoHostClasses)
}

tasks.processResources {
    from(demoHostArchive) { into("demo") }
}

application {
    mainClass.set("tvbox.engine.server.MainKt")
    applicationName = "tvbox-engine"
    // Spider classes converted from dex do not always pass the JVM verifier; Android does not verify them either.
    // The heap flags keep an idle engine small: by default the JVM commits a heap sized from the machine's RAM.
    applicationDefaultJvmArgs = listOf(
        "-Xss1m", "-XX:+UseSerialGC", "-Xms16m", "-Xmx1g", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
        "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote",
    )
}
