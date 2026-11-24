plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.jsoup)
    implementation(libs.kotlinx.coroutines.core)

    // org.json ships with Android; JVM consumers add it as a runtime dependency.
    compileOnly(libs.org.json)

    testImplementation(libs.org.json)
}
