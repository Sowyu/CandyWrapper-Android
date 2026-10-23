plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Release signing: CI writes the keystore from repository secrets and sets these variables
// (see .github/workflows/android.yml). Without them the release build uses the debug key.
val releaseKeystore: String? = System.getenv("CW_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "org.anikodev.candywrapper"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.anikodev.candywrapper"
        minSdk = 28
        targetSdk = 37
        // CI's run number only grows, so the in-app updater compares it (core.update.UpdateManifest).
        val run = System.getenv("GITHUB_RUN_NUMBER")
        val commit = System.getenv("GITHUB_SHA")?.take(7) ?: "dev"
        versionCode = (run ?: "1").toInt()
        versionName = "0.1.${run ?: "0"}-$commit"
        buildConfigField("String", "COMMIT", "\"$commit\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("CW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CW_KEY_ALIAS")
                keyPassword = System.getenv("CW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin")
    }
}

kotlin {
    jvmToolchain(17)
}

composeCompiler {
    // Core models are immutable data classes; let Compose skip rows whose instance did not change.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.profileinstaller)

    // JVM screenshot tests: Robolectric renders the composables, Roborazzi compares them to app/src/test/snapshots.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}
