plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "probe"
    compileSdk = 36
    defaultConfig { applicationId = "probe.nnapi"; minSdk = 34; targetSdk = 36; versionCode = 1; versionName = "1" }
    buildTypes { getByName("debug") { isDebuggable = false } }
    androidResources { noCompress += "tflite" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // the classic TensorFlow Lite runtime, which still carries the NNAPI delegate (LiteRT 2.x does not)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
