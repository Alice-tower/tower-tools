plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.proxyenvmanager"
version = "1.0.14"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.proxyenvmanager.MainKt"

        nativeDistributions {
            packageName = "ProxyEnvManager"
            packageVersion = "1.0.14"
            vendor = "Alice-tower"
            windows {
                iconFile.set(project.file("icons/app-icon.ico"))
            }
        }
    }
}
