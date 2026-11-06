plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

application {
    mainClass.set("dev.aragos.aor.training.TrainingMainKt")
    applicationDefaultJvmArgs =
        listOf(
            "-Xmx24g",
            "-XX:ActiveProcessorCount=8",
            "-XX:ParallelGCThreads=1",
            "-XX:ConcGCThreads=1",
        )
}

distributions {
    main {
        contents {
            from(rootProject.file("scripts/train-opponents.py")) {
                into("bin")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ai"))
    implementation(libs.jackson.databind)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.truth)
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures fixed self-play games and optionally checks a prior report's outcomes."
    workingDir(rootProject.projectDir)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.aragos.aor.training.TrainingBenchmark")
    jvmArgs(
        "-Xmx2g",
        "-XX:ActiveProcessorCount=2",
        "-XX:ParallelGCThreads=1",
        "-XX:ConcGCThreads=1",
    )
}
