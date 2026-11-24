import com.geno1024.ai.worm.gradle.Versioning
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// RULES/VERSION.md: configuration-time stamp; packaging builds POST first.
val stamp = Versioning.stamp(rootDir, project)

android {
    namespace = "com.geno1024.ai.worm.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geno1024.ai.worm.android"
        minSdk = 26
        targetSdk = 37
        versionCode = stamp.versionCode
        versionName = stamp.versionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // RULES/KEYSTORE.md: env first, git-ignored local.properties second.
    // No keystore only means release cannot replace an installed signed build.
    val signingProps = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun signingSecret(property: String, env: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: signingProps.getProperty(property)?.takeIf { it.isNotBlank() }

    val signingStoreFile = signingSecret("keystore.storeFile", "KEYSTORE_STORE_FILE")
    val signingStorePassword = signingSecret("keystore.storePassword", "KEYSTORE_STORE_PASSWORD")

    signingConfigs {
        if (signingStoreFile != null && signingStorePassword != null) {
            create("release") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = signingStorePassword
                keyPassword = signingSecret("keystore.keyPassword", "KEYSTORE_KEY_PASSWORD")
                    ?: signingStorePassword
                keyAlias = signingSecret("keystore.keyAlias", "KEYSTORE_KEY_ALIAS") ?: "geno"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":worm:core"))
    implementation(project(":worm:qzone:common"))
    implementation(project(":worm:qzone:shuoshuo"))
    implementation(project(":worm:qzone:album"))
    implementation(project(":worm:zhihu"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // Offline unit tests for the release parser. Android provides org.json on device;
    // local JVM tests need the real jar or the mockable android.jar would return defaults.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.org.json)
}
