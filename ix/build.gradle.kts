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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // for the harness and tests on the JVM (Android has it built in)
}

// Aligns every frame of each burst to its reference and writes the aligned frames (training data for the learned denoiser)
tasks.register<JavaExec>("alignDump") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.everettpenne.ix.AlignDumpKt")
    args = listOf(project.findProperty("data")?.toString() ?: "")
    jvmArgs("-Xmx6g")
}

// Runs the merge engine on real bursts from the camera's dataset mode; see src/test/kotlin/.../Harness.kt
tasks.register<JavaExec>("harness") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.everettpenne.ix.HarnessKt")
    args = listOf(project.findProperty("data")?.toString() ?: "")
    jvmArgs("-Xmx6g")
    if (project.hasProperty("dump")) systemProperty("harness.dump", "1")
    if (project.hasProperty("isoNoise")) systemProperty("harness.isoNoise", project.property("isoNoise").toString())
}
