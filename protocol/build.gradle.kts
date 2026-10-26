import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.maven.publish)
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
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.java.websocket)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
  useJUnitPlatform()
}

// Same setup as KStore: the Sonatype Central Portal, credentials and signing key from the
// mavenCentralUsername, mavenCentralPassword, signingInMemoryKey and signingInMemoryKeyPassword
// Gradle properties (ORG_GRADLE_PROJECT_* in release.yml). The deployment waits for a manual
// "Publish" on central.sonatype.com.
mavenPublishing {
  publishToMavenCentral()
  // A dev machine has no key, so publishToMavenLocal goes unsigned. release.yml fails without one
  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
  coordinates(group.toString(), "t3code-protocol", version.toString())
  // No Dokka here, so the javadoc jar Central wants is an empty one
  configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))

  pom {
    name.set("T3 Code protocol")
    description.set(
      "Plain Kotlin client for the T3 Code server: pairing, the thread list, threads and " +
        "the generated wire types. What the T3 Code JetBrains plugin talks to the server with.",
    )
    url.set("https://github.com/xxfast/t3-jetbrains-plugin")
    licenses {
      license {
        name.set("MIT")
        url.set("https://opensource.org/licenses/MIT")
      }
    }
    issueManagement {
      system.set("Github")
      url.set("https://github.com/xxfast/t3-jetbrains-plugin/issues")
    }
    scm {
      url.set("https://github.com/xxfast/t3-jetbrains-plugin")
      connection.set("scm:git:git://github.com/xxfast/t3-jetbrains-plugin.git")
      developerConnection.set("scm:git:ssh://git@github.com/xxfast/t3-jetbrains-plugin.git")
    }
    developers {
      developer {
        id.set("xxfast")
        name.set("Isuru Rajapakse")
        email.set("isurukusumal36@gmail.com")
      }
    }
  }
}
