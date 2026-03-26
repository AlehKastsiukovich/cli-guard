plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":adapter-codex-cli"))
    implementation(project(":adapter-gemini-cli"))
    implementation(project(":adapter-spi"))
    implementation(project(":core-guard"))
    implementation(project(":core-policy"))
    implementation(libs.pty4j)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "llm-guard"
    mainClass = "dev.alehkastsiukovich.llmguard.cli.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.run {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
