import org.gradle.process.CommandLineArgumentProvider

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktor) apply false
}

val kotlinFormatter by configurations.creating {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

dependencies {
    kotlinFormatter(libs.ktfmt)
}

abstract class KotlinSourcesArguments : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        sources.files.sortedBy(File::getPath).map(File::getAbsolutePath)
}

val kotlinSources =
    fileTree(rootDir) {
        include("**/*.kt", "**/*.kts")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/node_modules/**", ".git/**")
    }

fun registerKotlinFormatter(name: String, checkOnly: Boolean) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description =
            if (checkOnly) {
                "Checks Kotlin formatting without changing files."
            } else {
                "Formats Kotlin sources and Gradle scripts."
            }
        classpath = kotlinFormatter
        mainClass.set("com.facebook.ktfmt.cli.Main")
        args("--kotlinlang-style")
        if (checkOnly) {
            args("--dry-run", "--set-exit-if-changed")
            // JavaExec has no outputs by default, so an unchanged check otherwise runs ktfmt again.
            val checked = layout.buildDirectory.file("kotlin-format/$name.checked")
            outputs.file(checked)
            doLast {
                checked.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText("")
                }
            }
        }
        argumentProviders.add(
            objects.newInstance<KotlinSourcesArguments>().apply { sources.from(kotlinSources) }
        )
    }

registerKotlinFormatter("formatKotlin", checkOnly = false)

registerKotlinFormatter("checkKotlinFormat", checkOnly = true)
