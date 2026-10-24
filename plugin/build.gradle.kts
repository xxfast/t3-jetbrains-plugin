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
version = "0.1.0"

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

val localProperties: Properties? = rootProject.file("local.properties")
  .takeIf { it.exists() }
  ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

val localIde: String? = providers.gradleProperty("t3.localIde").orNull
  ?: localProperties?.getProperty("t3.localIde")

// Thread ids for runIde and runHarness to open on launch, comma-separated, see LaunchOpen
val openThreads: String = providers.gradleProperty("t3.open").orNull
  ?: localProperties?.getProperty("t3.open")
  ?: ""

val bundledModules: List<String> = listOf(
  "intellij.platform.jewel.foundation",
  "intellij.platform.jewel.ui",
  "intellij.platform.jewel.ideLafBridge",
  "intellij.platform.jewel.markdown.core",
  "intellij.platform.jewel.markdown.extensions.autolink",
  "intellij.platform.jewel.markdown.extensions.gfmStrikethrough",
  "intellij.platform.jewel.markdown.extensions.gfmTables",
  "intellij.platform.jewel.markdown.ideLafBridgeStyling",
  "intellij.libraries.compose.foundation.desktop",
  "intellij.libraries.compose.runtime.desktop",
  "intellij.libraries.skiko",
)

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
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
    // Always compile against the lowest platform we ship for. A zip compiled against a newer
    // local IDE links Jewel overloads that older IDEs don't have (#63). The local IDE only
    // runs the sandbox, see runHarness below.
    intellijIdea("2025.3")
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
      Pair with a running T3 server, work its threads from the T3 Sidebar and T3 Chat View tool
      windows, and open the files, diffs and terminals they touch in the IDE.
    """.trimIndent()
    changeNotes = """
      <b>0.1.0</b>
      <ul>
        <li>Pair with a T3 Code server using a pairing code or link.</li>
        <li>The Sidebar and the ChatView as tool windows, live as threads run.</li>
        <li>Send, steer and stop. Answer approvals and questions from the composer.</li>
        <li>Start threads, in the current checkout or a new worktree.</li>
        <li>The desktop's thread menu: pin, settle, snooze, rename, archive, delete.</li>
        <li>Files, diffs, review comments and the thread's terminal open in the IDE.</li>
        <li>Attach files, images and editor selections.</li>
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
      // The lowest platform we ship for, and whatever the newest EAP is today. The other
      // JetBrains IDEs share IDEA's platform build, so these two cover them.
      create(IntelliJPlatformType.IntellijIdea, "2025.3")
      latest {
        types = listOf(IntelliJPlatformType.IntellijIdea)
        channels = listOf(ProductRelease.Channel.EAP)
      }
      // Android Studio lags the platform and bundles its own Jewel; 261 is where #63 broke.
      // Rider is the other product with a frontend of its own.
      latest {
        types = listOf(IntelliJPlatformType.AndroidStudio, IntelliJPlatformType.Rider)
        channels = listOf(ProductRelease.Channel.RELEASE)
      }
      create(IntelliJPlatformType.AndroidStudio, "2026.1.3.7")
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
  systemProperty("t3code.open", openThreads)
}

intellijPlatformTesting {
  runIde {
    register("runHarness") {
      sandboxDirectory = layout.buildDirectory.dir("harness-sandbox")
      // Custom run tasks take disabled plugins from here, not from the withType above
      plugins { disablePlugins(distractingPlugins) }
      // An installed IDE runs the sandbox without the platform download. It never changes
      // what the plugin compiles against.
      if (localIde != null) localPath = file(localIde)
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
        systemProperty("t3code.open", openThreads)
      }
    }
  }
}
