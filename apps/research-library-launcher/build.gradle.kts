plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.researchlibrarylauncher"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.researchlibrarylauncher.MainKt"

        nativeDistributions {
            packageName = "ResearchLibraryLauncher"
            packageVersion = "1.0.0"
            vendor = "Alice-tower"
        }
    }
}
