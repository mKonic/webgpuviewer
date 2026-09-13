import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// scripts/version.sh is the only version source - releasing is tagging. The artifact drops the tag's
// leading v, so tag v1.2.3 publishes webgpuviewer 1.2.3.
fun versionOutput(kind: String): String = providers.exec {
    commandLine("bash", rootProject.file("scripts/version.sh").path, kind)
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("")

val versionName: String = versionOutput("name").ifEmpty { "unknown" }
val versionCode: Int = versionOutput("code").toIntOrNull() ?: 0
val artifactVersion: String = versionName.removePrefix("v")

android {
    namespace = "ca.mpreg.webgpuviewer"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")

        // For a host's crash log or debug screen - which viewer build a report came from.
        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")
        buildConfigField("int", "VERSION_CODE", "$versionCode")

        externalNativeBuild {
            cmake {
                cppFlags("-O3 -flto")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val embed: Configuration = configurations.create("embed")
configurations.named("compileOnly") { extendsFrom(embed) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)

    embed(libs.androidx.webgpu)
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val mergeTask =
            project.tasks.register<MergeEmbeddedAarsTask>(
                "merge${variant.name.replaceFirstChar { it.uppercase() }}EmbeddedAars"
            ) {
                embedAars.from(embed)
            }
        variant.artifacts
            .use(mergeTask)
            .wiredWithFiles(
                MergeEmbeddedAarsTask::inputAar,
                MergeEmbeddedAarsTask::outputAar
            )
            .toTransform(SingleArtifact.AAR)
    }
}

/**
 * The ivy descriptor a host resolves the release AAR through - a GitHub release is not a Maven
 * repository, so the dependencies travel beside the AAR. Lists this build's direct runtime
 * dependencies at the versions it resolved; androidx.webgpu is merged into the AAR, so it is not one.
 */
val writeIvyDescriptor by tasks.registering {
    val runtime = configurations.named("releaseRuntimeClasspath")
        .flatMap { it.incoming.resolutionResult.rootComponent }
    val descriptor = layout.buildDirectory.file("outputs/ivy/ivy-$artifactVersion.xml")
    inputs.property("version", artifactVersion)
    outputs.file(descriptor)
    doLast {
        val dependencies = runtime.get().dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .filterNot { dependency ->
                // A BOM shapes versions but ships no artifact.
                val attributes = dependency.resolvedVariant.attributes
                val category = attributes.keySet().firstOrNull { it.name == "org.gradle.category" }
                attributes.getAttribute(category ?: return@filterNot false).toString().endsWith("platform")
            }
            .mapNotNull { it.selected.moduleVersion }
            .distinctBy { "${it.group}:${it.name}" }
            .sortedBy { "${it.group}:${it.name}" }
            .joinToString("\n") {
                """        <dependency org="${it.group}" name="${it.name}" rev="${it.version}" conf="default->default"/>"""
            }
        descriptor.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<ivy-module version="2.0">
            |    <info organisation="ca.mpreg" module="webgpuviewer" revision="$artifactVersion"/>
            |    <configurations>
            |        <conf name="default"/>
            |    </configurations>
            |    <publications>
            |        <artifact name="webgpuviewer" type="aar" ext="aar" conf="default"/>
            |    </publications>
            |    <dependencies>
            |$dependencies
            |    </dependencies>
            |</ivy-module>
            |""".trimMargin()
        )
    }
}

afterEvaluate {
    mavenPublishing {
        coordinates("ca.mpreg", "webgpuviewer", artifactVersion)

        pom {
            name.set("webgpuviewer")
            description.set("webgpuviewer")
            inceptionYear.set("2026")
            url.set("https://github.com/mpreg-ca/webgpuviewer")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("wwww-wwww")
                    name.set("w")
                    url.set("https://github.com/wwww-wwww/")
                }
            }
            scm {
                url.set("https://github.com/mpreg-ca/webgpuviewer/")
                connection.set("scm:git:git://github.com/mpreg-ca/webgpuviewer.git")
                developerConnection.set("scm:git:ssh://git@github.com/mpreg-ca/webgpuviewer.git")
            }
        }

        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }
}

/**
 * Merges the classes.jar, native libs (jni/), and assets from [embedAars] into [inputAar],
 * producing [outputAar]. Used to embed androidx.webgpu directly into this library's own AAR
 * so downstream consumers don't need to resolve it (or its custom repo) separately.
 */
abstract class MergeEmbeddedAarsTask : DefaultTask() {
    @get:InputFile
    abstract val inputAar: RegularFileProperty

    @get:InputFiles
    abstract val embedAars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputAar: RegularFileProperty

    @TaskAction
    fun merge() {
        val outFile = outputAar.get().asFile
        outFile.parentFile.mkdirs()
        if (outFile.exists()) outFile.delete()

        val ownAarFile = inputAar.get().asFile
        val embedAarFiles = embedAars.files

        val mergedClassesJarBytes = mergeClassesJars(ownAarFile, embedAarFiles)

        ZipFile(ownAarFile).use { ownZip ->
            ZipOutputStream(outFile.outputStream()).use { zos ->
                val writtenPaths = mutableSetOf<String>()

                fun writeEntry(name: String, bytes: ByteArray) {
                    if (!writtenPaths.add(name)) return
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }

                for (entry in ownZip.entries()) {
                    if (entry.isDirectory) continue
                    if (entry.name == "classes.jar") {
                        writeEntry("classes.jar", mergedClassesJarBytes)
                    } else {
                        writeEntry(entry.name, ownZip.getInputStream(entry).readBytes())
                    }
                }

                for (embedAarFile in embedAarFiles) {
                    ZipFile(embedAarFile).use { embedZip ->
                        for (entry in embedZip.entries()) {
                            if (entry.isDirectory) continue
                            if (entry.name == "classes.jar") continue
                            if (entry.name == "AndroidManifest.xml") continue
                            writeEntry(entry.name, embedZip.getInputStream(entry).readBytes())
                        }
                    }
                }
            }
        }
    }

    private fun mergeClassesJars(ownAarFile: File, embedAarFiles: Set<File>): ByteArray {
        val tmpJar = File.createTempFile("merged-classes", ".jar")
        try {
            ZipOutputStream(tmpJar.outputStream()).use { zos ->
                val seen = mutableSetOf<String>()

                fun addClassesJarFrom(aarFile: File) {
                    ZipFile(aarFile).use { aarZip ->
                        val classesEntry = aarZip.getEntry("classes.jar") ?: return
                        val tmpIn = File.createTempFile("classes-in", ".jar")
                        try {
                            aarZip.getInputStream(classesEntry).use { input ->
                                tmpIn.outputStream().use { input.copyTo(it) }
                            }
                            ZipFile(tmpIn).use { classesZip ->
                                for (entry in classesZip.entries()) {
                                    if (entry.isDirectory) continue
                                    if (!seen.add(entry.name)) continue
                                    zos.putNextEntry(ZipEntry(entry.name))
                                    classesZip.getInputStream(entry).use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } finally {
                            tmpIn.delete()
                        }
                    }
                }

                // Our own classes come first so they win any (unexpected) name conflicts.
                addClassesJarFrom(ownAarFile)
                for (embedAarFile in embedAarFiles) {
                    addClassesJarFrom(embedAarFile)
                }
            }
            return tmpJar.readBytes()
        } finally {
            tmpJar.delete()
        }
    }
}
