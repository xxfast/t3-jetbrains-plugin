package io.github.xxfast.t3code.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

class SmokeTest {
  @Serializable
  data class Thread(val id: String, val title: String? = null)

  @Test
  fun ignoresUnknownKeys() {
    val json: String = """{"id":"t1","title":"hello","somethingNew":42}"""
    val thread: Thread = T3Json.decodeFromString(Thread.serializer(), json)
    assertEquals(Thread(id = "t1", title = "hello"), thread)
  }

  @Test
  fun dropsNullsAndDefaults() {
    val encoded: String = T3Json.encodeToString(Thread.serializer(), Thread(id = "t1"))
    assertEquals("""{"id":"t1"}""", encoded)
  }
}
