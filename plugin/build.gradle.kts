import java.util.Properties
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
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
    // Optional at runtime, see t3code-terminal.xml
    bundledPlugin("org.jetbrains.plugins.terminal")
    pluginVerifier()
    zipSigner()
    testFramework(TestFrameworkType.Platform)
  }

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testRuntimeOnly(libs.junit4)
}

tasks.buildPlugin {
  archiveBaseName = "t3-code"
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
    description = """
      Unofficial native client for <a href="https://github.com/pingdotgg/t3code">T3 Code</a>.
      Pair with a running T3 server, browse its threads in a tool window, and open the files and
      diffs they touch in the IDE.
    """.trimIndent()
    changeNotes = """
      <b>0.1</b>
      <ul>
        <li>Pair with a T3 Code server using a pairing code or link.</li>
        <li>Thread list and thread view in the T3 Code tool window.</li>
        <li>Open files and diffs from a thread in the IDE.</li>
      </ul>
    """.trimIndent()
    vendor {
      name = "xxfast"
    }
    ideaVersion {
      sinceBuild = "253"
      untilBuild = provider { null }
    }
  }

  pluginVerification {
    ides {
      // The lowest platform we ship for, and whatever the newest EAP is today
      create(IntelliJPlatformType.IntellijIdea, "2025.3")
      latest {
        types = listOf(IntelliJPlatformType.IntellijIdea)
        channels = listOf(ProductRelease.Channel.EAP)
      }
    }
  }

  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }

  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
  }
}

// Air's agent panel and its ACP agents get in the way, in every sandbox
val distractingPlugins: List<String> = listOf("com.intellij.air")

tasks.withType<PrepareSandboxTask>().configureEach {
  disabledPlugins.addAll(distractingPlugins)
}

tasks.runIde {
  // Open this repo as the project, the tool windows don't exist on the welcome screen
  args(rootProject.projectDir.absolutePath)
}

intellijPlatformTesting {
  runIde {
    register("runHarness") {
      sandboxDirectory = layout.buildDirectory.dir("harness-sandbox")
      // Custom run tasks take disabled plugins from here, not from the withType above
      plugins { disablePlugins(distractingPlugins) }
      // A scenario is a folder under harness/scenarios, picked with -Pt3.scenario=<name>
      val scenario: String = providers.gradleProperty("t3.scenario").getOrElse("default")
      val scenarios: File = rootProject.layout.projectDirectory.dir("harness/scenarios").asFile
      val fixtures: File = scenarios.resolve(scenario)
      require(fixtures.isDirectory) {
        val known: String = scenarios.listFiles { file -> file.isDirectory }.orEmpty()
          .map { it.name }.sorted().joinToString()
        "No harness scenario '$scenario' in $scenarios. Known: $known"
      }
      task {
        // Open this repo as the project, the tool window doesn't exist on the welcome screen
        args(rootProject.projectDir.absolutePath)
        systemProperty("t3code.fixtures", fixtures.absolutePath)
      }
    }
  }
}
