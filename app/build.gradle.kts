plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from the environment, never from the repository: HOLTZMAN_RELEASE_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD.
val signingEnv = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { System.getenv("HOLTZMAN_RELEASE_$it")?.takeIf(String::isNotBlank) }
val canSign = signingEnv.values.all { it != null }

android {
    namespace = "com.everettpenne.holtzman"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.everettpenne.holtzman"
        // The Pixel 10a first (Android 17). Android 14+ because Ultra HDR needs the Gainmap API; nothing below is promised.
        minSdk = 34
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
        // this phone (and every Pixel since the 6) is 64-bit ARM; dropping the other ABIs leaves out about 25 MB of unused native code
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
        if (canSign) create("release") {
            storeFile = file(signingEnv.getValue("STORE_FILE")!!)
            storePassword = signingEnv.getValue("STORE_PASSWORD"); keyAlias = signingEnv.getValue("KEY_ALIAS"); keyPassword = signingEnv.getValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            if (canSign) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") { applicationIdSuffix = ".next"; isDebuggable = false }
    }
    // the denoiser model is read straight from the APK by the interpreter, so it must stay uncompressed
    androidResources { noCompress += "tflite" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":ix"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // The suite's liquid-glass kit (theme, type, controls): ../Glass, via includeBuild.
    implementation("com.everettpenne:glass")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    // On-device inference on the Tensor G4's TPU through NNAPI: the classic TensorFlow Lite runtime, which still carries the NNAPI delegate (LiteRT 2.x does not,
    // and its own route to the TPU is refused for apps off Google's list; docs/ix-pipeline.md).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
