plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":adapter-gemini-cli"))
    implementation(project(":adapter-spi"))
    implementation(project(":core-guard"))
    implementation(project(":core-policy"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "llm-guard"
    mainClass = "dev.alehkastsiukovich.llmguard.cli.MainKt"
}

tasks.run {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
