import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

/**
 * Google Maps SDK key for the example app's map, read from the gitignored `local.properties` so
 * it is never committed. Set `MAPS_API_KEY=...` there, or supply it as the `MAPS_API_KEY`
 * environment variable in CI.
 *
 * Absent, the build still succeeds and the app still runs — only the map renders blank, which is
 * the right trade for a module whose point is demonstrating the client library rather than the
 * map.
 */
val mapsApiKey: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromFile = if (localProperties.exists()) {
        Properties().apply { localProperties.inputStream().use { load(it) } }
            .getProperty("MAPS_API_KEY")
    } else {
        null
    }
    fromFile ?: System.getenv("MAPS_API_KEY") ?: ""
}

android {
    namespace = "com.msight.app.androidapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.msight.app.androidapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.androidx.material.icons.core)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(project(":shared"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}