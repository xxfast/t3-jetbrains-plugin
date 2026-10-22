package io.github.xxfast.t3code.protocol

import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.util.concurrent.Flow

/** How much of an upload body goes to the connection at a time, and so how often progress ticks. */
internal const val UPLOAD_CHUNK_BYTES: Int = 64 * 1024

/**
 * [bytes] as a request body that tells [onProgress] how many bytes it has handed to the
 * connection so far, the JDK's stand-in for XHR's `upload.onprogress`. Fed in
 * [UPLOAD_CHUNK_BYTES] slices so progress moves, with the full length declared so the request
 * still carries `Content-Length`. A resubscription (a retry inside the client) counts from zero.
 */
internal fun countingBodyPublisher(
  bytes: ByteArray,
  onProgress: (sent: Long) -> Unit,
  chunkBytes: Int = UPLOAD_CHUNK_BYTES,
): HttpRequest.BodyPublisher {
  // Sliced lazily, so a large upload isn't held twice
  val chunks: Iterable<ByteArray> = Iterable {
    (bytes.indices step chunkBytes).asSequence()
      .map { start -> bytes.copyOfRange(start, minOf(start + chunkBytes, bytes.size)) }
      .iterator()
  }
  val inner: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofByteArrays(chunks)
  return object : HttpRequest.BodyPublisher {
    override fun contentLength(): Long = bytes.size.toLong()

    override fun subscribe(subscriber: Flow.Subscriber<in ByteBuffer>) {
      inner.subscribe(CountingSubscriber(subscriber, onProgress))
    }
  }
}

private class CountingSubscriber(
  private val downstream: Flow.Subscriber<in ByteBuffer>,
  private val onProgress: (sent: Long) -> Unit,
) : Flow.Subscriber<ByteBuffer> {
  private var sent: Long = 0

  override fun onSubscribe(subscription: Flow.Subscription) = downstream.onSubscribe(subscription)

  override fun onNext(item: ByteBuffer) {
    // Before handing it on: the connection drains the buffer
    val size: Int = item.remaining()
    downstream.onNext(item)
    sent += size
    onProgress(sent)
  }

  override fun onError(throwable: Throwable) = downstream.onError(throwable)

  override fun onComplete() = downstream.onComplete()
}
