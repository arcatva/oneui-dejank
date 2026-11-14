plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The module and the app are released together; module.prop is the one place
// the version lives.
val moduleProp: Map<String, String> = rootProject.file("module/module.prop").readLines()
    .mapNotNull { line ->
        val i = line.indexOf('=')
        if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
    }.toMap()

// -Pscreenshots adds the Robolectric tests that render the screens to PNG files.
val screenshots = providers.gradleProperty("screenshots").isPresent

// The release key is never in the repository: CI gets it from its secrets.
// Without it the build is signed with the debug key of the machine.
val keystore: String? = System.getenv("DEJANK_KEYSTORE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "dev.arcatva.dejank"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.arcatva.dejank"
        minSdk = 31
        targetSdk = 36
        versionCode = moduleProp.getValue("versionCode").toInt()
        versionName = moduleProp.getValue("version").removePrefix("v")
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("DEJANK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEJANK_KEY_ALIAS")
                keyPassword = System.getenv("DEJANK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }

    if (screenshots) {
        sourceSets.getByName("test").kotlin.srcDir("src/screenshotTest/java")
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    // Material 3 Expressive lives in the 1.5 line.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")

    if (screenshots) {
        testImplementation("org.robolectric:robolectric:4.15.1")
        testImplementation("androidx.compose.ui:ui-test-junit4:1.12.0")
        debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
    }
}
