plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

group = "dev.aragos.aor"

version = "1.0.0"

application {
    mainClass = "dev.aragos.aor.ApplicationKt"
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.jackson.databind)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.truth)
}
