plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "dev.towertools.resourcetagger"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.11.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.11.1")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.towertools.resourcetagger.MainKt"

        nativeDistributions {
            modules("java.sql", "jdk.unsupported")
            packageName = "ResourceTagger"
            packageVersion = "1.0.0"
            vendor = "Alice-tower"
        }
    }
}
