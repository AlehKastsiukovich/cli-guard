plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "dev.alehkastsiukovich.llmguard"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

