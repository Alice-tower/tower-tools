plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.mediatranscriber"
version = "1.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    exclude("**/RealEnvironmentAcceptanceTest*")
}

tasks.register<Test>("realIntegrationTest") {
    description = "Runs optional integration checks against locally installed FFmpeg assets."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/RealEnvironmentAcceptanceTest*")
}

compose.desktop {
    application {
        mainClass = "dev.towertools.mediatranscriber.MainKt"

        nativeDistributions {
            packageName = "MediaTranscriber"
            packageVersion = "1.0.1"
            vendor = "Alice-tower"
        }
    }
}
