plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":worm:core"))
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
}
