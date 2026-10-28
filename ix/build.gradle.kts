import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

/** Ix is deliberately Android-free (no Context, no Uri, no Compose): the engine's tests run on the JVM alone. */
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        explicitApi()
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
