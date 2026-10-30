plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

kotlin {
  jvmToolchain(21)
}

application {
  mainClass.set("io.github.xxfast.t3code.cli.MainKt")
  applicationName = "t3"
  // Thread titles aren't ASCII, and a Windows console's code page mangles them otherwise
  applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// gradle.properties turns the default stdlib off for the plugin, and :protocol has its
// dependencies compileOnly, so a standalone app brings its own
dependencies {
  implementation(project(":protocol"))
  implementation(libs.kotlin.stdlib)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}
