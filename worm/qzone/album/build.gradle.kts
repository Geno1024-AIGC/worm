plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":worm:qzone:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.org.json)
}

application {
    mainClass.set("com.geno1024.ai.worm.qzone.album.MainKt")
}
