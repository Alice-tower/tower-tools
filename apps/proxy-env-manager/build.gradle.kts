plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.proxyenvmanager"
version = "1.1.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.proxyenvmanager.MainKt"

        nativeDistributions {
            packageName = "ProxyEnvManager"
            packageVersion = "1.1.1"
            vendor = "Alice-tower"
            windows {
                iconFile.set(project.file("icons/app-icon.ico"))
            }
        }
    }
}
