package com.github.nava2.aff4.streams

import com.github.nava2.aff4.io.buffer
import com.github.nava2.aff4.io.sourceProvider
import com.github.nava2.aff4.meta.rdf.model.ImageStream
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Source
import java.nio.ByteBuffer

internal class Aff4Bevy(
  fileSystem: FileSystem,
  imageStreamConfig: ImageStream,
  private val bevyIndexReader: BevyIndexReader,
  private val bevyChunkCache: BevyChunkCache,
  private val bevy: Bevy,
) : AutoCloseable {
  private val sourceProviderWithRefCounts = SourceProviderWithRefCounts(::readAt)

  private val compressionMethod = imageStreamConfig.compressionMethod
  private val chunkSize = imageStreamConfig.chunkSize

  private var position: Long = 0L

  private val chunkBuffer = ByteBuffer.allocateDirect(chunkSize)

  init {
    chunkBuffer.limit(0)
  }

  private val compressedChunkBuffer = ByteBuffer.allocateDirect(chunkSize)

  private var lastDataSourcePosition = 0L

  val bevySize: Long by lazy {
    val currentPosition = position

    try {
      val indexValueCount = fileSystem.metadata(bevy.indexSegment).size!! / IndexValue.SIZE_BYTES
      val lastChunkBevyIndex = (indexValueCount - 1) * chunkSize
      position = lastChunkBevyIndex
      readIntoBuffer()

      lastChunkBevyIndex + chunkBuffer.limit()
    } finally {
      position = currentPosition
      chunkBuffer.limit(0)
    }
  }

  private val dataSourceProvider = fileSystem.sourceProvider(bevy.dataSegment).buffer()
  private var dataSource: BufferedSource? = null

  fun source(position: Long): Source {
    return sourceProviderWithRefCounts.source(position)
  }

  override fun close() {
    sourceProviderWithRefCounts.close()

    dataSource?.close()
    lastDataSourcePosition = 0
  }

  private fun readAt(readPosition: Long, sink: Buffer, byteCount: Long): Long {
    when {
      position == bevySize -> return -1L
      byteCount == 0L -> return 0L
    }

    moveTo(readPosition)

    val maxBytesToRead = byteCount.coerceAtMost(bevySize - position)
    var remainingBytes = maxBytesToRead

    do {
      if (!chunkBuffer.hasRemaining()) {
        readIntoBuffer()

        if (!chunkBuffer.hasRemaining()) break
      }

      val readSlice = chunkBuffer.slice(
        chunkBuffer.position(),
        remainingBytes.toInt().coerceAtMost(chunkBuffer.remaining()),
      )

      val readIntoSink = sink.write(readSlice)
      chunkBuffer.position(chunkBuffer.position() + readIntoSink)

      remainingBytes -= readIntoSink
      position += readIntoSink
    } while (remainingBytes > 0 && readIntoSink > 0)

    return maxBytesToRead - remainingBytes
  }

  private fun moveTo(newPosition: Long) {
    if (position == newPosition) return

    val currentChunkIndex = (position - chunkBuffer.remaining()).floorDiv(chunkSize)
    val newChunkIndex = newPosition.floorDiv(chunkSize)

    val newPositionInChunk = (newPosition - newChunkIndex * chunkSize).toInt()
    if (newChunkIndex == currentChunkIndex) {
      chunkBuffer.position(newPositionInChunk)
    } else {
      // set our selves to be "zeroed"
      chunkBuffer.limit(0)
    }

    position = newPosition
  }

  private fun readIntoBuffer() {
    val index = bevyIndexReader.readIndexContaining(bevy, position) ?: return
    check(index.compressedLength <= chunkSize) {
      "Read invalid compressed chunk index.length: ${index.compressedLength}"
    }

    chunkBuffer.rewind()

    bevyChunkCache.getOrPutInto(bevy, index, chunkBuffer) {
      readCompressedBuffer(index.dataPosition, index.compressedLength)

      val chunkBufferLength = compressionMethod.uncompress(compressedChunkBuffer, chunkBuffer)

      chunkBuffer.limit(chunkBufferLength)

      if (chunkBufferLength <= index.compressedLength) {
        // data wasn't compressed, so 1-1 copy it
        chunkBuffer.rewind()
        chunkBuffer.limit(compressedChunkBuffer.limit())
        chunkBuffer.put(compressedChunkBuffer)
      }

      chunkBuffer.rewind()
    }

    chunkBuffer.position((position % chunkSize).toInt())
  }

  private fun readCompressedBuffer(dataPosition: Long, byteCount: Int) {
    if (dataSource == null || dataPosition < lastDataSourcePosition) {
      dataSource?.close()
      dataSource = dataSourceProvider.get()
      lastDataSourcePosition = 0
    }

    dataSource!!.apply {
      skip(dataPosition - lastDataSourcePosition)
      lastDataSourcePosition = dataPosition

      compressedChunkBuffer.rewind()
      compressedChunkBuffer.limit(byteCount)

      while (compressedChunkBuffer.hasRemaining()) {
        val dataRead = read(compressedChunkBuffer)
        lastDataSourcePosition += dataRead

        if (dataRead == 0) break
      }
    }

    // check after such that `lastDataSourcePosition is always correct
    check(!compressedChunkBuffer.hasRemaining())

    compressedChunkBuffer.rewind()
  }
}
