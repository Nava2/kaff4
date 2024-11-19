package com.github.nava2.aff4.logical

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.github.nava2.aff4.io.SourceProvider
import com.github.nava2.aff4.io.buffer
import com.github.nava2.aff4.model.Aff4Model
import com.github.nava2.aff4.model.Aff4StreamOpener
import com.github.nava2.aff4.model.get
import com.github.nava2.aff4.model.rdf.Aff4Arn
import com.github.nava2.aff4.model.rdf.FileImage
import okio.Buffer
import okio.BufferedSource
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout
import java.util.Objects

private const val ENTRY_CACHE_SIZE: Long = 512

@Suppress("TooManyFunctions")
internal class Aff4LogicalFileSystem(
  private val aff4Model: Aff4Model,
  private val aff4StreamOpener: Aff4StreamOpener,
  internal val rootEntry: Directory,
) : FileSystem() {

  private val entryCache: LoadingCache<Path, Entry?> = Caffeine.newBuilder()
    .maximumSize(ENTRY_CACHE_SIZE)
    .build { path ->
      var entry: Entry? = rootEntry

      val segments = path.segments.asSequence()
        .map { it.toPath() }

      for (segment in segments.take(path.segments.size - 1)) {
        if (entry !is Directory) return@build null
        entry = entry.children[segment]
      }

      val lastSegment = path.segments.last().toPath()
      (entry as? Directory)?.children?.get(lastSegment)
    }

  override fun canonicalize(path: Path): Path {
    return path.normalized()
  }

  override fun list(dir: Path): List<Path> {
    return listOrNull(dir) ?: throw FileNotFoundException("$dir does not exist")
  }

  override fun listOrNull(dir: Path): List<Path>? {
    val directory = findEntryOrNull(dir)
    return if (directory is Directory) {
      directory.children.values.map { it.canonicalPath }
    } else {
      null
    }
  }

  override fun metadataOrNull(path: Path): FileMetadata? {
    return findEntryOrNull(path)?.toFileMetadata(aff4Model)
  }

  override fun openReadOnly(file: Path): FileHandle {
    val (entry, streamProvider) = getEntryAndStreamProvider(file)
      ?: throw FileNotFoundException("file not found or is not a file")
    return FileHandleImpl(entry, streamProvider)
  }

  override fun source(file: Path): Source {
    val (_, streamProvider) = getEntryAndStreamProvider(file)
      ?: throw FileNotFoundException("file not found or is not a file")
    return streamProvider.source(timeout = Timeout.NONE)
  }

  private fun getEntryAndStreamProvider(file: Path): Pair<File, SourceProvider<Source>>? {
    val entry = findEntryOrNull(file)
    if (entry !is File) return null

    val streamProvider = aff4StreamOpener.openStream(entry.arn)
    return entry to streamProvider
  }

  private fun findEntryOrNull(path: Path): Entry? = entryCache.get(path)

  data class Aff4ExtraData(
    val originalFileName: Path,
    val recordChangedAtMillis: Long?,
  )

  internal sealed interface Entry {
    val arn: Aff4Arn?
    val canonicalPath: Path

    val originalPath: Path

    fun toFileMetadata(model: Aff4Model): FileMetadata
  }

  internal data class Directory(
    override val arn: Aff4Arn?,
    override val canonicalPath: Path,
    override val originalPath: Path,
    val children: Map<Path, Entry>,
  ) : Entry {
    override fun toFileMetadata(model: Aff4Model): FileMetadata {
      return FileMetadata(
        isRegularFile = true,
        isDirectory = true,
        symlinkTarget = null,
        size = null,
        createdAtMillis = null,
        lastModifiedAtMillis = null,
        lastAccessedAtMillis = null,
      )
    }
  }

  internal data class File(
    override val arn: Aff4Arn,
    override val canonicalPath: Path,
    override val originalPath: Path,
    val size: Long,
  ) : Entry {
    override fun toFileMetadata(model: Aff4Model): FileMetadata {
      val aff4FileImage = model.get<FileImage>(arn)
      return FileMetadata(
        isRegularFile = true,
        isDirectory = false,
        symlinkTarget = null,
        size = size,
        createdAtMillis = aff4FileImage.birthTime?.toInstant()?.toEpochMilli(),
        lastModifiedAtMillis = aff4FileImage.lastWritten?.toInstant()?.toEpochMilli(),
        lastAccessedAtMillis = aff4FileImage.lastAccessed?.toInstant()?.toEpochMilli(),
        extras = mapOf(
          Aff4ExtraData::class to Aff4ExtraData(
            originalFileName = aff4FileImage.originalFileName,
            recordChangedAtMillis = aff4FileImage.recordChanged?.toInstant()?.toEpochMilli(),
          )
        )
      )
    }
  }

  //region Mutation methods
  override fun appendingSink(file: Path, mustExist: Boolean): Sink {
    throwReadOnly()
  }

  override fun atomicMove(source: Path, target: Path) {
    throwReadOnly()
  }

  override fun createDirectory(dir: Path, mustCreate: Boolean) {
    throwReadOnly()
  }

  override fun createSymlink(source: Path, target: Path) {
    throwReadOnly()
  }

  override fun delete(path: Path, mustExist: Boolean) {
    throwReadOnly()
  }

  override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
    throwReadOnly()
  }

  override fun sink(file: Path, mustCreate: Boolean): Sink {
    throwReadOnly()
  }

  private fun throwReadOnly(): Nothing {
    throw UnsupportedOperationException("${Aff4LogicalFileSystem::class.simpleName} is read-only.")
  }
  //endregion
}

private class FileHandleImpl(
  private val entry: Aff4LogicalFileSystem.File,
  sourceProvider: SourceProvider<Source>,
) : FileHandle(readWrite = false) {
  private val sourceProvider: SourceProvider<BufferedSource> = sourceProvider.buffer()
  private var source: BufferedSource? = null
  private var position = Long.MAX_VALUE
  private val seekBuffer = Buffer()

  override fun protectedClose() {
    source?.close()
  }

  override fun protectedSize(): Long = entry.size

  override fun protectedFlush() = Unit

  override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
    val currentSource = source
    val source = if (currentSource == null ||
      position > fileOffset ||
      fileOffset - position >= SEEK_SIZE
    ) {
      currentSource?.close()
      source = sourceProvider.source(fileOffset, Timeout.NONE)
      source!!
    } else {
      currentSource.read(seekBuffer, fileOffset - position)
      currentSource
    }

    return source.read(array, arrayOffset, byteCount)
  }

  override fun protectedResize(size: Long) {
    error("Not supported")
  }

  override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
    error("Not supported")
  }

  override fun equals(other: Any?): Boolean {
    return other is FileHandleImpl && entry == other.entry
  }

  override fun hashCode(): Int {
    return Objects.hash(this::class, entry)
  }

  companion object {
    private const val SEEK_SIZE = 512
  }
}
