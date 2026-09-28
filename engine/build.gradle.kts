plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

tasks.test {
    useJUnitPlatform()
    // Host native bridge (scripts/build_native_host.sh) for tests that exercise real models.
    systemProperty("kestrel.native.dir", rootProject.file("build/native-host").absolutePath)
    System.getenv("KESTREL_TEST_MODELS")?.let { environment("KESTREL_TEST_MODELS", it) }
}
