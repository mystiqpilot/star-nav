import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties in the repository root (never committed) or STARNAV_*
// environment variables (CI). Without either, release builds use the local debug key, which
// is what the developer's own phone has installed; CI sets STARNAV_REQUIRE_RELEASE_KEY.
val releaseKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    fun get(prop: String, env: String) = props.getProperty(prop) ?: System.getenv(env)
    val storeFile = get("storeFile", "STARNAV_STORE_FILE") ?: return@run null
    mapOf(
        "storeFile" to storeFile,
        "storePassword" to (get("storePassword", "STARNAV_STORE_PASSWORD") ?: ""),
        "keyAlias" to (get("keyAlias", "STARNAV_KEY_ALIAS") ?: ""),
        "keyPassword" to (get("keyPassword", "STARNAV_KEY_PASSWORD") ?: ""),
    )
}
if (releaseKey == null && System.getenv("STARNAV_REQUIRE_RELEASE_KEY") == "true") {
    throw GradleException("Release key required: set keystore.properties or STARNAV_STORE_FILE and friends")
}

android {
    namespace = "tools.apachi.starlinknav"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "tools.apachi.starlinknav"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.5.5"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // foss: Android's own location services only (GitHub, F-Droid). play: adds Google Play
    // services to put Google's fused location into mock mode (Google Play, GitHub).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    dependenciesInfo {
        // The dependency block is encrypted for Google; keep it out of APKs (F-Droid flags it).
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Lists the app under Settings > System > Languages > App languages (Android 13+),
        // with the languages that have a values-xx folder.
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        // MapLibre's native library: compressed in the APK (about 4 MB per ABI instead of 11).
        jniLibs.useLegacyPackaging = true
    }

    // One APK per ABI plus a universal one for GitHub releases; Play gets the bundle.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.maplibre.android)
    "playImplementation"(libs.play.services.location)
}
