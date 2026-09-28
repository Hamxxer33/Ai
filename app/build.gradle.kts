import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional release signing from environment (CI secrets) or keystore.properties; otherwise the
// release build is signed with the debug key so that it is installable for testing.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = System.getenv(env) ?: signingProps.getProperty(key)

android {
    namespace = "io.kestrel.research"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("kestrel.ndkVersion").orNull ?: "29.0.14206865"

    defaultConfig {
        applicationId = "io.kestrel.research"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = providers.gradleProperty("kestrel.cmakeVersion").orNull ?: "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "KESTREL_KEYSTORE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "KESTREL_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KESTREL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KESTREL_KEY_PASSWORD")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // ggml loads its per-CPU backend variants with dlopen() from nativeLibraryDir, so the
        // libraries must be extracted from the APK at install time.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**") }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", layout.buildDirectory.dir("generated/benchAssets").get().asFile)
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += listOf("MissingTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val copyBenchAssets by tasks.registering(Copy::class) {
    from(rootProject.file("benchmark/questions.jsonl"))
    into(layout.buildDirectory.dir("generated/benchAssets/benchmark"))
}
tasks.named("preBuild") { dependsOn(copyBenchAssets) }

dependencies {
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.sqlite:sqlite:2.5.1")
    implementation("androidx.sqlite:sqlite-bundled:2.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test"))
}
