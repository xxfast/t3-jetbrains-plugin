import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.intellij.platform)
}

group = "io.github.xxfast"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

val localIde: String? = providers.gradleProperty("t3.localIde").orNull
  ?: rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    ?.getProperty("t3.localIde")

val bundledModules: List<String> = listOf(
  "intellij.platform.jewel.foundation",
  "intellij.platform.jewel.ui",
  "intellij.platform.jewel.ideLafBridge",
  "intellij.platform.jewel.markdown.core",
  "intellij.platform.jewel.markdown.ideLafBridgeStyling",
  "intellij.libraries.compose.foundation.desktop",
  "intellij.libraries.compose.runtime.desktop",
  "intellij.libraries.skiko",
)

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
    // A newer local IDE ships stdlib metadata our compiler can't read yet
    if (localIde != null) freeCompilerArgs.add("-Xskip-metadata-version-check")
  }
}

configurations.runtimeClasspath {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
  exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
  exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

dependencies {
  implementation(project(":protocol"))
  // The IDE's copy is an internal module from 2026.3, plugins can't depend on it. Bundle ours.
  implementation(libs.kotlinx.serialization.json)

  intellijPlatform {
    if (localIde != null) local(localIde) else intellijIdea("2025.3")
    bundledModules.forEach { module -> bundledModule(module) }
    pluginVerifier()
    zipSigner()
    testFramework(TestFrameworkType.Platform)
  }

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  // The platform test framework drags in JUnit Vintage, which can't start next to Jupiter 5.13
  useJUnitPlatform { includeEngines("junit-jupiter") }
  systemProperty(
    "t3code.scenarios",
    rootProject.layout.projectDirectory.dir("harness/scenarios").asFile.absolutePath,
  )
}

intellijPlatform {
  pluginConfiguration {
    id = "io.github.xxfast.t3code"
    name = "T3 Code"
    version = project.version.toString()
    description = "A native client for T3 Code's server, in your IDE."
    vendor {
      name = "xxfast"
    }
    ideaVersion {
      sinceBuild = "253"
      untilBuild = provider { null }
    }
  }
}

intellijPlatformTesting {
  runIde {
    register("runHarness") {
      sandboxDirectory = layout.buildDirectory.dir("harness-sandbox")
      task {
        // Open this repo as the project, the tool window doesn't exist on the welcome screen
        args(rootProject.projectDir.absolutePath)
        systemProperty(
          "t3code.fixtures",
          rootProject.layout.projectDirectory.dir("harness/scenarios").asFile.absolutePath,
        )
      }
    }
  }
}
