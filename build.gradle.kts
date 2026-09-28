// Plugin versions live here (buildscript classpath) so every module shares one Kotlin plugin
// classloader, and the Android Gradle Plugin is only resolved when :app is part of the build.
buildscript {
    val kotlinVersion = "2.2.21"
    val agpVersion = "8.13.2"
    val jvmOnly = (project.findProperty("kestrel.jvmOnly") as String?) == "true"
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (!jvmOnly) {
            classpath("com.android.tools.build:gradle:$agpVersion")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}

allprojects {
    group = "io.kestrel"
    version = "0.1.0"
}
