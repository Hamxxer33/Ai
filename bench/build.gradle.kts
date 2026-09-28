plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// Bytecode targets Java 17 so the same engine jar runs on Android (D8) and on any JDK >= 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

application {
    mainClass.set("io.kestrel.bench.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Xmx2g", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
        "-Dkestrel.native.dir=" + rootProject.file("build/native-host").absolutePath,
    )
}
