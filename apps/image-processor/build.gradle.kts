plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.imageprocessor"
version = "1.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.14.0")
    implementation("com.twelvemonkeys.imageio:imageio-tiff:3.14.0")
    implementation("com.twelvemonkeys.imageio:imageio-bmp:3.14.0")
    implementation("com.github.usefulness:webp-imageio:0.11.0")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.imageprocessor.MainKt"

        nativeDistributions {
            packageName = "ImageProcessor"
            packageVersion = "1.1.0"
            vendor = "Alice-tower"
        }
    }
}
