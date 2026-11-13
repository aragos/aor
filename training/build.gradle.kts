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
                    "automata_evolution.py",
                    "automata_policy_search.py",
                    "automata_purchase_continuation.py",
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
                    "joint_state_corpus.py",
                    "purchase_ecology.py",
                    "purchase_ecology_data.py",
                    "purchase_ecology_environment.py",
                    "purchase_storage.py",
                    "purchase_value_data.py",
                    "purchase_value_benchmark.py",
                    "afterstate_value_data.py",
                    "purchase_plan_predictor.py",
                    "purchase_plan_ranking.py",
                    "token_policy.py",
                    "token_order_policy.py",
                    "token_order_metadata.py",
                    "token_paired_policy.py",
                    "token_paired_identity.py",
                    "token_paired_data.py",
                    "token_paired_fit.py",
                    "token_paired_validation.py",
                    "token_paired_campaign_plan.py",
                    "token_paired_storage.py",
                    "token_paired_handoff.py",
                    "token_paired_corpus.py",
                    "token_paired_admission.py",
                    "token_paired_numeric_stage.py",
                    "token_paired_campaign.py",
                    "token_paired_execution.py",
                    "token_paired_process.py",
                    "token_paired_campaign_audit.py",
                    "token_paired_screen.py",
                    "paired_policy_math.py",
                    "cpe_paired_policy.py",
                    "cpe_paired_data.py",
                    "cpe_paired_fit.py",
                    "cpe_paired_completed.py",
                    "cpe_paired_validation.py",
                    "cpe_paired_campaign_plan.py",
                    "cpe_paired_storage.py",
                    "cpe_paired_handoff.py",
                    "cpe_paired_corpus.py",
                    "cpe_paired_admission.py",
                    "cpe_paired_numeric_stage.py",
                    "cpe_paired_campaign.py",
                    "cpe_paired_execution.py",
                    "cpe_paired_process.py",
                    "cpe_paired_campaign_audit.py",
                    "cpe_paired_screen.py",
                    "coordinated_es_math.py",
                    "coordinated_es_calibration.py",
                    "coordinated_es_projection.py",
                    "coordinated_es_numeric.py",
                    "coordinated_es_plan.py",
                    "coordinated_es_storage.py",
                    "coordinated_es_handoff.py",
                    "coordinated_es_numeric_stage.py",
                    "coordinated_es_campaign.py",
                    "coordinated_es_execution.py",
                    "coordinated_es_process.py",
                    "coordinated_es_generation_audit.py",
                    "coordinated_es_admission.py",
                    "coordinated_es_audit.py",
                    "coordinated_es_screen.py",
                    "expansion_policy.py",
                    "expansion_mc.py",
                    "expansion_on_policy_data.py",
                    "expansion_on_policy_loop.py",
                    "expansion_state_baseline.py",
                    "expansion_previous_baseline.py",
                    "expansion_baseline_loop.py",
                    "expansion_training_evidence.py",
                    "expansion_baseline_audit.py",
                    "expansion_baseline_screen.py",
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

tasks.withType<Test>().configureEach {
    outputs.dir(layout.buildDirectory.dir("test-fixtures/cpe-wire"))
    outputs.file(layout.buildDirectory.file("test-fixtures/coordinated-es-browser-parity.json"))
    outputs.dir(layout.buildDirectory.dir("test-fixtures/coordinated-es-wire"))
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
