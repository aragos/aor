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

tasks.register<Test>("fastTest") {
    group = "verification"
    description = "Runs server tests except complete-game HTTP acceptance; test runs all coverage."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    exclude("**/FullGameHttpTest*")
    shouldRunAfter(tasks.test)
}

tasks.register<JavaExec>("verifyGameLogs") {
    group = "verification"
    description =
        "Replays an administrative JSON export with the current engine without writing games."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.GameLogReplayKt")
    args(providers.gradleProperty("gameLogs").orElse("").get())
}

tasks.register<JavaExec>("describeEuropeanMap") {
    group = "verification"
    description =
        "Validates the European map draft and reports differences from the standard board."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.EuropeanMapReportKt")
    if (providers.gradleProperty("fullMap").orNull == "true") {
        args("--full")
    }
}

tasks.register<JavaExec>("exportMapCatalog") {
    group = "verification"
    description = "Exports a resolved map catalog for offline geometry generation and validation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.MapCatalogExportKt")
    args(
        providers.gradleProperty("map").orElse("standard").get(),
        providers.gradleProperty("mapOutput").orElse("").get(),
    )
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

// The generated browser catalog is verified against this domain export in CI.
tasks.register<JavaExec>("exportRuleOptions") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.RuleOptionsExportKt")
    args(providers.gradleProperty("ruleOptionsOutput").get())
}
