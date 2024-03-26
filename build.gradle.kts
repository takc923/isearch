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

    patchPluginXml {
        sinceBuild.set("231")
        pluginDescription.set(
            """
<p>isearch plugin.</p>
<p>This plugin adds isearch-forward and isearch-backward action which are like Emacs.</p>
<p>Default keymap(Mac)</p>
<ul>
  <li>isearch-forward:  Control-S</li>
  <li>isearch-backward: Control-R</li>
</ul>
""".trimIndent()
        )

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
"""
    }
}
