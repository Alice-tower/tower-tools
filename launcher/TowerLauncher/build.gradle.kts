plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.launcher"
version = "1.0.3"

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
}

compose.desktop {
    application {
        mainClass = "dev.towertools.launcher.MainKt"

        nativeDistributions {
            packageName = "TowerLauncher"
            packageVersion = "1.0.3"
            vendor = "Alice-tower"
        }
    }
}
