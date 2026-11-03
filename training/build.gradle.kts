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
