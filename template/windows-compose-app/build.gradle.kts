plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "__APP_ID__"
version = "__APP_VERSION__"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("org.jetbrains.compose.material:material:1.11.1")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "__APP_PACKAGE__.MainKt"

        nativeDistributions {
            packageName = "__APP_PROJECT_NAME__"
            packageVersion = "__APP_VERSION__"
            vendor = "Alice-tower"
            windows {
                iconFile.set(project.file("icons/app-icon.ico"))
            }
        }
    }
}
