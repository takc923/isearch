plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = "io.github.takc923"
version = "0.13-SNAPSHOT"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3.5")
    updateSinceUntilBuild.set(false)
    pluginName.set("isearch")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
}
