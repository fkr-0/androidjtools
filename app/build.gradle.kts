import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing inputs come from ANDROID_KEYSTORE_* env vars (CI) or a git-ignored
// keystore.properties (local). Without them the release variant is debug-signed and
// unofficial; -PrequireReleaseSigning=true turns that fallback into a build failure.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun signingInput(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseSigningInputs = mapOf(
    "storeFile" to signingInput("ANDROID_KEYSTORE_FILE", "storeFile"),
    "storePassword" to signingInput("ANDROID_KEYSTORE_PASSWORD", "storePassword"),
    "keyAlias" to signingInput("ANDROID_KEY_ALIAS", "keyAlias"),
    "keyPassword" to signingInput("ANDROID_KEY_PASSWORD", "keyPassword"),
)
val missingSigningInputs = releaseSigningInputs.filterValues { it == null }.keys
val releaseSigningConfigured = missingSigningInputs.isEmpty()
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"

if (requireReleaseSigning && !releaseSigningConfigured) {
    throw GradleException("Release signing is required but missing: ${missingSigningInputs.sorted().joinToString()}")
}
if (releaseSigningConfigured && !file(releaseSigningInputs.getValue("storeFile")!!).isFile) {
    throw GradleException("Release keystore not found: ${releaseSigningInputs.getValue("storeFile")}")
}
if (!releaseSigningConfigured && missingSigningInputs.size < releaseSigningInputs.size) {
    throw GradleException("Incomplete release signing configuration; missing: ${missingSigningInputs.sorted().joinToString()}")
}

android {
    namespace = "dev.androidjtools"
    compileSdk = 35
    ndkVersion = "25.0.8775105"
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { ndkPath = it }

    defaultConfig {
        applicationId = "dev.androidjtools"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.0-rc.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningInputs.getValue("storeFile")!!)
                storePassword = releaseSigningInputs.getValue("storePassword")
                keyAlias = releaseSigningInputs.getValue("keyAlias")
                keyPassword = releaseSigningInputs.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseSigningConfigured) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
