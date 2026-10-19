package io.github.xxfast.t3code.protocol

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class RpcConnectionTest {
  private val server: FakeRpcServer = FakeRpcServer().startAndWait()

  @AfterTest
  fun stop() = server.close()

  private suspend fun CoroutineScope.connected(block: suspend (RpcConnection) -> Unit) {
    val rpc: RpcConnection = RpcConnection.open(defaultHttpClient(), server.uri, "test", this)
    try {
      block(rpc)
    } finally {
      rpc.close()
    }
  }

  @Test
  fun unaryRoundTrip() = realTimeTest {
    connected { rpc ->
      val payload: JsonObject = buildJsonObject {
        put("cwd", "/repo")
        put("relativePath", "README.md")
      }
      val result: JsonObject = rpc.call("projects.readFile", payload).jsonObject
      assertEquals("# hi", result.string("contents"))
      assertEquals("Bearer test", server.authorization)
      assertTrue("orchestrationProtocol=2" in server.resource.orEmpty())
      val request: JsonObject = server.received.first { it.string("_tag") == "Request" }
      assertEquals("0", request.string("id"))
    }
  }

  @Test
  fun streamAcksOnlyAfterTheCollectorTakesTheChunk() = realTimeTest {
    connected { rpc ->
      val values: MutableList<JsonElement> = mutableListOf()
      rpc.stream("orchestration.subscribeShell", JsonObject(emptyMap())).take(2).collect { value ->
        values += value
        if (values.size == 1) {
          delay(300)
          assertEquals(emptyList(), server.events, "Acked before the collector was done")
        }
      }
      assertEquals(listOf("ack:0", "second:0"), server.events.take(2))
      assertEquals("snapshot", values[0].jsonObject.string("kind"))
      assertEquals("thread.updated", values[1].jsonObject.string("kind"))
      eventually { server.received.any { it.string("_tag") == "Interrupt" } }
    }
  }

  @Test
  fun missingScopeIsScopeRequired() = realTimeTest {
    connected { rpc ->
      val error: T3Exception.ScopeRequired = assertFailsWith {
        rpc.call("review.getDiffFileContents", JsonObject(emptyMap()))
      }
      assertEquals("filesystem:read", error.requiredPermission)
      assertEquals("orchestration:read", error.requiredScope)
    }
  }
}
