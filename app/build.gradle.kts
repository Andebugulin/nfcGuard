import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.andebugulin.nfcguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andebugulin.nfcguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                val props = Properties().apply {
                    load(FileInputStream(propsFile))
                }
                storeFile = file("keystore/release.jks")
                storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = props.getProperty("KEY_ALIAS", "guardian")
                keyPassword = props.getProperty("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    // Domain builders used by BOTH the Robolectric and instrumented suites.
    // Robolectric-only helpers stay in src/test; they cannot be shared because
    // Robolectric is not on the instrumented classpath.
    sourceSets {
        getByName("test").java.srcDir("src/testShared/java")
        getByName("androidTest").java.srcDir("src/testShared/java")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// The Compose test manifest (which supplies the ComponentActivity every
// `createComposeRule` test launches into) ships as `debugImplementation`, so the
// release unit-test variant cannot host a Compose test at all — it fails with
// "Unable to resolve activity for Intent ... androidx.activity.ComponentActivity".
// Release unit tests would otherwise re-run the identical sources for no extra
// signal (minification does not apply to unit tests), so the variant is turned
// off and `./gradlew test` means debug + :domain, as TESTS.md documents.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

dependencies {
    // Pure-Kotlin domain: AppState + state-machine logic objects.
    implementation(project(":domain"))

    // Existing dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization (still needed for ConfigManager.ExportData, etc.)
    implementation(libs.kotlinx.serialization.json)

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit tests (SanityTest only — domain tests live in :domain)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Instrumented tests (real device: NFC, accessibility, overlay, widget)
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

}

afterEvaluate {
    tasks.matching {
        it.name.contains("ArtProfile") ||
                it.name.contains("StartupProfile") ||
                it.name.contains("VersionControlInfo")
    }.configureEach {
        enabled = false
    }
}