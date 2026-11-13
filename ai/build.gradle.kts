plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    js {
        useEsModules()
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.truth)
        }
    }
}

// This constructed JVM fixture is consumed by the production-JS and isolated browser checks.
tasks.named<Test>("jvmTest") {
    outputs.file(layout.buildDirectory.file("test-fixtures/expansion-browser-parity.json"))
}
