package io.github.xxfast.t3code.protocol

import java.io.ByteArrayOutputStream
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CountingBodyPublisherTest {
  @Test
  fun theBodyArrivesWholeWithItsLengthAndProgressCountsUpToIt() {
    val bytes: ByteArray = ByteArray(10) { it.toByte() }
    val progress: MutableList<Long> = mutableListOf()
    val publisher: HttpRequest.BodyPublisher =
      countingBodyPublisher(bytes, { sent -> progress += sent }, chunkBytes = 4)

    assertEquals(10L, publisher.contentLength())
    assertContentEquals(bytes, drain(publisher))
    assertEquals(listOf(4L, 8L, 10L), progress)
  }

  @Test
  fun aResubscriptionCountsFromZero() {
    val progress: MutableList<Long> = mutableListOf()
    val publisher: HttpRequest.BodyPublisher =
      countingBodyPublisher(ByteArray(3), { sent -> progress += sent }, chunkBytes = 2)
    drain(publisher)
    drain(publisher)
    assertEquals(listOf(2L, 3L, 2L, 3L), progress)
  }

  private fun drain(publisher: HttpRequest.BodyPublisher): ByteArray {
    val out = ByteArrayOutputStream()
    val done = CountDownLatch(1)
    publisher.subscribe(object : Flow.Subscriber<ByteBuffer> {
      override fun onSubscribe(subscription: Flow.Subscription) {
        subscription.request(Long.MAX_VALUE)
      }

      override fun onNext(item: ByteBuffer) {
        val chunk = ByteArray(item.remaining())
        item.get(chunk)
        out.write(chunk)
      }

      override fun onError(throwable: Throwable) = done.countDown()

      override fun onComplete() = done.countDown()
    })
    assertTrue(done.await(5, TimeUnit.SECONDS))
    return out.toByteArray()
  }
}
