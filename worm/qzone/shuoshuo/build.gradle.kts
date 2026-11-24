plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":worm:qzone:common"))
    implementation(libs.kotlinx.coroutines.core)
    // compileOnly in core/common; JVM CLI needs it at runtime (Android has it in the platform).
    implementation(libs.org.json)
}

application {
    mainClass.set("com.geno1024.ai.worm.qzone.shuoshuo.MainKt")
}
