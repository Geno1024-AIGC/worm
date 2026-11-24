import com.geno1024.ai.worm.gradle.Versioning
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

subprojects {
    // :worm and :worm:qzone are grouping directories, not buildable modules.
    if (!projectDir.resolve("build.gradle.kts").isFile) return@subprojects

    group = "com.geno1024.worm"

    // The stamp is read at configuration time; for packaging builds it POSTs
    // to the build-count service first (RULES/VERSION.md). No count.pack here.
    val stamp = Versioning.stamp(rootDir, project)
    version = stamp.versionName

    tasks.register("printVersionName") {
        group = "help"
        description = "Prints the five-part version derived from Git and build-count."
        doLast { println(version) }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            // Android consumes the very same jars; stay at 17 so D8 can read them.
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        dependencies {
            add("testImplementation", platform(libs.junit.bom))
            add("testImplementation", libs.junit.jupiter.api)
            add("testRuntimeOnly", libs.junit.jupiter.engine)
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    pluginManager.withPlugin("application") {
        tasks.register<Jar>("distFatJar") {
            group = "build"
            description = "Assembles a self-contained runnable jar."
            archiveClassifier.set("all")
            val app = project.extensions.getByType(JavaApplication::class.java)
            val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
            manifest { attributes["Main-Class"] = app.mainClass.get() }
            from(main.output)
            from({
                project.configurations.getByName("runtimeClasspath")
                    .filter { it.name.endsWith(".jar") }
                    .map { zipTree(it) }
            })
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
}
