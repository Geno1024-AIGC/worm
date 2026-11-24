plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":worm:core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.org.json)
}

application {
    mainClass.set("com.geno1024.ai.worm.zhihu.MainKt")
}
