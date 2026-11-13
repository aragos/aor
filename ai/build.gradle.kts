plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

tasks.withType<Test>().configureEach {
    outputs.file(layout.buildDirectory.file("test-fixtures/cpe-browser-parity.json"))
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
