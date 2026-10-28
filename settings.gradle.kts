pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "HoltzmanCamera"
include(":app", ":ix")
// The shared liquid-glass UI kit lives in its own repo next to this one.
includeBuild("../Glass")
