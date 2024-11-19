package com.github.nava2.aff4.logical

import com.github.nava2.aff4.model.Aff4Model
import com.github.nava2.aff4.model.Aff4StreamOpener
import com.github.nava2.aff4.model.query
import com.github.nava2.aff4.model.rdf.Aff4Arn
import com.github.nava2.aff4.model.rdf.FileImage
import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.streams.asStream

internal class Aff4LogicalFileSystemFactory @Inject constructor() {
  fun open(aff4Model: Aff4Model, streamOpener: Aff4StreamOpener): Aff4LogicalFileSystem {
    val rootEntry = MutableDirectory(
      arn = null,
      canonicalPath = "".toPath(),
      originalPath = "".toPath(),
      children = ConcurrentHashMap(),
    )

    aff4Model.query<FileImage>()
      .asStream()
      .parallel()
      .forEach { file ->
        var directoryRecord = rootEntry
        for (segment in file.originalFileName.segments.dropLast(1).map { it.toPath(normalize = true) }) {
          val childPath = directoryRecord.canonicalPath / segment
          val fromParent = directoryRecord.children.computeIfAbsent(segment) {
            MutableDirectory(
              arn = null,
              canonicalPath = childPath,
              originalPath = childPath,
              children = ConcurrentHashMap(),
            )
          }
          if (fromParent !is MutableDirectory) {
            error("Found file where directory was expected: $childPath existing $fromParent")
          }
          directoryRecord = fromParent
        }

        val fileSegment = file.originalFileName.segments.last().toPath(true)
        val fileRecord = MutableFile(
          arn = file.arn,
          canonicalPath = directoryRecord.canonicalPath / fileSegment,
          originalPath = file.originalFileName,
          size = file.size,
        )
        directoryRecord.children[fileSegment] = fileRecord
      }

    return Aff4LogicalFileSystem(aff4Model, streamOpener, rootEntry.toDirectory())
  }

  sealed interface MutableEntry {
    var arn: Aff4Arn?
    val canonicalPath: Path

    val originalPath: Path
  }

  private data class MutableDirectory(
    override var arn: Aff4Arn?,
    override val canonicalPath: Path,
    override val originalPath: Path,
    val children: ConcurrentHashMap<Path, MutableEntry>,
  ) : MutableEntry {
    fun toDirectory(): Aff4LogicalFileSystem.Directory {
      val readOnlyChildren = children.mapValues { (_, entry) ->
        when (entry) {
          is MutableFile -> entry.toFile()
          is MutableDirectory -> entry.toDirectory()
        }
      }
      return Aff4LogicalFileSystem.Directory(arn, canonicalPath, originalPath, readOnlyChildren)
    }
  }

  private data class MutableFile(
    override var arn: Aff4Arn?,
    override var canonicalPath: Path,
    override var originalPath: Path,
    val size: Long,
  ) : MutableEntry {
    fun toFile(): Aff4LogicalFileSystem.File = Aff4LogicalFileSystem.File(arn!!, canonicalPath, originalPath, size)
  }
}
