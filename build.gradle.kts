// Top-level build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.8.2")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
