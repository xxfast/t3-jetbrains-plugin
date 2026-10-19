plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  explicitApi()
  jvmToolchain(21)
}

dependencies {
  compileOnly(libs.kotlin.stdlib)
  compileOnly(libs.kotlinx.coroutines.core)
  compileOnly(libs.kotlinx.serialization.json)

  testImplementation(libs.kotlin.stdlib)
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}
