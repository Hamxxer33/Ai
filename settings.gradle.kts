pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kestrel"

// :engine and :bench are plain JVM modules and build anywhere with a JDK.
// :app needs the Android SDK and Google's Maven repository; pass -Pkestrel.jvmOnly=true to skip it.
include(":engine", ":bench")
if (providers.gradleProperty("kestrel.jvmOnly").orNull != "true") {
    include(":app")
}
