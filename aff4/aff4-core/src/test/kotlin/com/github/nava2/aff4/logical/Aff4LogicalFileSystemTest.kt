package com.github.nava2.aff4.logical

import com.github.nava2.aff4.Aff4ImageTestModule
import com.github.nava2.aff4.Dream
import com.github.nava2.aff4.UnderTest
import com.github.nava2.aff4.io.md5
import com.github.nava2.aff4.io.sha1
import com.github.nava2.aff4.model.Aff4Model
import com.github.nava2.aff4.model.Aff4StreamOpener
import com.github.nava2.test.GuiceModule
import okio.Path.Companion.toPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.inject.Inject

class Aff4LogicalFileSystemTest {
  @GuiceModule
  val module = Aff4ImageTestModule.Dream

  @Inject
  @field:UnderTest
  private lateinit var aff4Model: Aff4Model

  @Inject
  @field:UnderTest
  private lateinit var aff4StreamOpener: Aff4StreamOpener

  @Inject
  private lateinit var aff4LogicalFileSystemFactory: Aff4LogicalFileSystemFactory

  @Test
  fun `verify dream file and directory`() {
    val fileSystem = aff4LogicalFileSystemFactory.open(aff4Model, aff4StreamOpener)
    assertThat(fileSystem.rootEntry.children).hasSize(1)

    val dreamPath = "test_images/AFF4-L/dream.txt".toPath()

    assertThat(fileSystem.list("test_images/AFF4-L".toPath()))
      .singleElement()
      .isEqualTo(dreamPath)

    val metadata = fileSystem.metadata(dreamPath)
    assertThat(metadata.isRegularFile).isTrue()
    assertThat(metadata.size).isEqualTo(8688)

    assertThat(metadata.extra(Aff4LogicalFileSystem.Aff4ExtraData::class)?.originalFileName)
      .isEqualTo("./test_images/AFF4-L/dream.txt".toPath())

    fileSystem.source(dreamPath).use { source ->
      assertThat(source)
        .md5(8688, "75d83773f8d431a3ca91bfb8859e486d")
    }
    fileSystem.source(dreamPath).use { source ->
      assertThat(source)
        .sha1(8688, "9ae1b46bead70c322eef7ac8bc36a8ea2055595c")
    }
  }
}
