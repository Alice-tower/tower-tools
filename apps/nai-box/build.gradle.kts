plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.naibox"
version = "1.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.naibox.MainKt"

        nativeDistributions {
            packageName = "NaiBox"
            packageVersion = "1.0.1"
            vendor = "Alice-tower"
            windows {
                iconFile.set(project.file("icons/app-icon.ico"))
            }
        }
    }
}
