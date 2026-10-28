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

tasks.register<JavaExec>("verifyGameLogs") {
    group = "verification"
    description =
        "Replays an administrative JSON export with the current engine without writing games."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.GameLogReplayKt")
    args(providers.gradleProperty("gameLogs").orElse("").get())
}

tasks.register<JavaExec>("importFileStorage") {
    group = "application"
    description =
        "Atomically imports a stopped current-format file store into an empty PostgreSQL schema."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.StorageImportKt")
    args(providers.gradleProperty("storageImport").orElse("").get())
}

tasks.register<JavaExec>("postgresBrowserServer") {
    group = "verification"
    description = "Runs an isolated PostgreSQL-backed server for browser verification."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.PostgresBrowserServerKt")
}

dependencies {
    api(project(":core"))
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.firebase.admin)
    implementation(libs.ktor.serverBodyLimit)
    implementation(libs.ktor.serverCompression)
    implementation(libs.ktor.serverDefaultHeaders)
    implementation(libs.logback)
    implementation(libs.jackson.databind)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.truth)
    testImplementation(libs.embedded.postgres)
    testImplementation(enforcedPlatform(libs.postgres.binaries))
    testRuntimeOnly(libs.postgres.darwin.arm)
}
