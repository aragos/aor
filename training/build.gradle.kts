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
            from(rootProject.file("scripts")) {
                include(
                    "train-opponents.py",
                    "automata_campaign.py",
                    "automata_experiments.py",
                    "automata_series.py",
                    "card-advantage-run.py",
                    "card_advantage_experiment.py",
                    "card_advantage_compact_experiment.py",
                    "card_advantage_priority_experiment.py",
                    "card_advantage_breadth_experiment.py",
                    "card_continuation_experiment.py",
                    "card_continuation_analysis.py",
                    "card_policy_coupling_experiment.py",
                    "card_policy_coupling_analysis.py",
                    "joint_economy_experiment.py",
                    "joint_economy.py",
                    "joint_trajectory.py",
                    "joint_current_ecology.py",
                    "joint_credit_targets.py",
                    "joint_phase_objective.py",
                    "joint_credit_update.py",
                    "joint_state_predictor.py",
                    "joint_state_value_data.py",
                    "purchase_value_data.py",
                    "purchase_value_benchmark.py",
                    "token_policy.py",
                    "token_order_policy.py",
                    "token_order_metadata.py",
                    "expansion_policy.py",
                    "territory_trajectory.py",
                    "territory_value.py",
                    "territory_value_trajectory.py",
                    "card_trajectory_experiment.py",
                    "card_trajectory_analysis.py",
                    "card_advantage_capacity.py",
                    "card_advantage_evaluation.py",
                    "card_advantage_train.py",
                    "card_advantage_data.py",
                    "card_advantage.py",
                    "card_policy.py",
                    "purchase_policy.py",
                )
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
