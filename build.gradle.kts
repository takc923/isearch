import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "io.github.takc923"
version = "0.13-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2.1")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.takc923.isearch"
        name = "isearch"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "252"
        }
        description = """
<p>isearch plugin.</p>
<p>This plugin adds isearch-forward and isearch-backward action which are like Emacs.</p>
<p>Default keymap(Mac)</p>
<ul>
  <li>isearch-forward:  Control-S</li>
  <li>isearch-backward: Control-R</li>
</ul>
""".trimIndent()
        changeNotes = """
<p>v0.12</p>
<ul>
  <li>Update dependencies</li>
</ul>
<p>v0.11</p>
<ul>
  <li>Update dependencies for 2020.1</li>
</ul>
<p>v0.10</p>
<ul>
  <li>Support only 193.1784+</li>
  <li>Refactoring</li>
  <li>Update dependencies</li>
</ul>
<p>v0.9</p>
<ul>
  <li>Fix bug that crashes if using with specific plugin</li>
  <li>Fix bug that causes if using 2019.1 or later</li>
  <li>Support only 2019.1 or later</li>
</ul>
<p>v0.8</p>
<ul>
  <li>Support 2018.3~</li>
  <li>Drop ~2018.2</li>
</ul>
<p>v0.7</p>
<ul>
  <li>Support 2018.1~2018.2.*</li>
  <li>Some internal changes which includes kotlin update and refactorings</li>
</ul>
<p>v0.6</p>
<ul>
  <li>Support paste</li>
  <li>Wrapped search</li>
  <li>Backspace to history back</li>
  <li>Highlight all caret's matching text</li>
  <li>Remove wildcard feature</li>
</ul>
<p>v0.5</p>
<ul>
  <li>Support multiple carets</li>
</ul>
""".trimIndent()
    }
}
