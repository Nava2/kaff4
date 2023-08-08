package net.navatwo.kaff4.streams.image_stream

import net.navatwo.kaff4.io.AbstractSource
import net.navatwo.kaff4.io.BufferedSource
import net.navatwo.kaff4.io.SourceProvider
import net.navatwo.kaff4.io.read
import okio.Buffer
import okio.Timeout
import java.nio.ByteBuffer

internal class Aff4BevySource(
  context: Aff4BevySourceContext,
  private var position: Long,
  timeout: Timeout,
) : AbstractSource(timeout) {

  private val compressionMethod = context.imageStream.compressionMethod
  private val chunkSize = context.imageStream.chunkSize

  private val imageBlockHashVerification = context.imageBlockHashVerification
  private val bevyIndexReader = context.bevyIndexReader
  private val bevyChunkCache = context.bevyChunkCache
  private val bevy = context.bevy

  private var lastDataSourcePosition = 0L

  private val dataSourceProvider: SourceProvider<BufferedSource> = context.dataSegmentSourceProvider()
  private var dataSource: BufferedSource? = null

  private val uncompressedSize = context.uncompressedSize

  private val chunkBuffer = ByteBuffer.allocateDirect(chunkSize)

  init {
    chunkBuffer.limit(0)
  }

  private val compressedChunkBuffer = ByteBuffer.allocateDirect(chunkSize)

  override fun protectedRead(sink: Buffer, byteCount: Long): Long {
    val maxBytesToRead = byteCount.coerceAtMost(uncompressedSize - position)

    if (!chunkBuffer.hasRemaining()) {
      readIntoBuffer()
    }

    val readSlice = chunkBuffer.slice(
      chunkBuffer.position(),
      maxBytesToRead.toInt().coerceAtMost(chunkBuffer.remaining()),
    )

    val readIntoSink = sink.write(readSlice)
    chunkBuffer.position(chunkBuffer.position() + readIntoSink)

    position += readIntoSink
    return readIntoSink.toLong()
  }

  override fun exhausted(): Exhausted = Exhausted.from(position == uncompressedSize)

  override fun protectedClose() {
    dataSource?.close()
    lastDataSourcePosition = 0
  }

  private fun readIntoBuffer() {
    val index = bevyIndexReader.readIndexContaining(position, timeout()) ?: return
    check(index.compressedLength <= chunkSize) {
      "Read invalid compressed chunk index.length: ${index.compressedLength}"
    }

    checkClosedOrTimedOut()

    chunkBuffer.rewind()
    chunkBuffer.limit(chunkBuffer.capacity())

    bevyChunkCache.getOrPutInto(bevy, index, chunkBuffer) {
      readCompressedBuffer(timeout(), index.dataPosition, index.compressedLength)

      val chunkBufferLength = compressionMethod.uncompress(compressedChunkBuffer, chunkBuffer)

      chunkBuffer.limit(chunkBufferLength)

      if (chunkBufferLength <= index.compressedLength) {
        // data wasn't compressed, so 1-1 copy it
        chunkBuffer.rewind()
        chunkBuffer.limit(compressedChunkBuffer.limit())
        chunkBuffer.put(compressedChunkBuffer)
      }

      chunkBuffer.rewind()

      imageBlockHashVerification.verifyBlock(bevy, position.floorDiv(chunkSize), chunkBuffer, timeout())
    }

    chunkBuffer.position((position % chunkSize).toInt())
  }

  private fun readCompressedBuffer(timeout: Timeout, dataPosition: Long, byteCount: Int) {
    if (dataSource == null || dataPosition < lastDataSourcePosition) {
      dataSource?.close()
      dataSource = dataSourceProvider.source(timeout)
      lastDataSourcePosition = 0
    }

    dataSource!!.apply {
      skip(dataPosition - lastDataSourcePosition)
      lastDataSourcePosition = dataPosition

      compressedChunkBuffer.rewind()
      compressedChunkBuffer.limit(byteCount)

      while (compressedChunkBuffer.hasRemaining()) {
        val dataRead = read(compressedChunkBuffer)
        if (dataRead == -1) {
          error("Failed to read expected data from source into compressed chunk buffer.")
        }

        lastDataSourcePosition += dataRead

        if (dataRead == 0) break
      }
    }

    // check after such that `lastDataSourcePosition is always correct
    check(!compressedChunkBuffer.hasRemaining())

    compressedChunkBuffer.rewind()
  }
}
