package com.github.nava2.aff4.streams

import com.github.nava2.aff4.Aff4CoreModule
import com.github.nava2.aff4.ForImages
import com.github.nava2.aff4.ForResources
import com.github.nava2.aff4.io.relativeTo
import com.github.nava2.aff4.meta.rdf.MemoryRdfRepositoryConfiguration
import com.github.nava2.aff4.meta.rdf.RdfRepositoryConfiguration
import com.github.nava2.aff4.meta.rdf.model.Hash
import com.github.nava2.aff4.meta.rdf.model.ImageStream
import com.github.nava2.aff4.model.Aff4Model
import com.github.nava2.aff4.streams.compression.SnappyModule
import com.github.nava2.guice.KAbstractModule
import com.github.nava2.test.GuiceTestRule
import com.google.inject.Provides
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.rdf4j.model.ValueFactory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import javax.inject.Singleton

class Aff4ImageStreamTest {
  @get:Rule
  val rule: GuiceTestRule = GuiceTestRule(
    Aff4CoreModule,
    SnappyModule,
    object : KAbstractModule() {
      override fun configure() {
        bind<RdfRepositoryConfiguration>().toInstance(MemoryRdfRepositoryConfiguration)
      }

      @Provides
      @Singleton
      @ForImages
      fun providesFileSystemForImages(@ForResources resourcesFileSystem: FileSystem): FileSystem {
        return resourcesFileSystem.relativeTo("images".toPath())
      }
    }
  )

  @Inject
  @field:ForImages
  private lateinit var imagesFileSystem: FileSystem

  @Inject
  private lateinit var aff4ModelLoader: Aff4Model.Loader

  @Inject
  private lateinit var valueFactory: ValueFactory

  @Inject
  private lateinit var bevyFactory: Bevy.Factory

  private lateinit var aff4Model: Aff4Model

  private lateinit var imageStreamConfig: ImageStream
  private lateinit var aff4ImageStream: Aff4ImageStream

  private val chunkSize: Long
    get() = imageStreamConfig.chunkSize.toLong()

  @Before
  fun setup() {
    aff4Model = aff4ModelLoader.load(imagesFileSystem, "Base-Linear.aff4".toPath())
    val imageStreamIri = valueFactory.createIRI("aff4://c215ba20-5648-4209-a793-1f918c723610")
    imageStreamConfig = aff4Model.get(imageStreamIri, ImageStream::class)

    aff4ImageStream = Aff4ImageStream(bevyFactory, aff4Model.imageRootFileSystem, imageStreamConfig)
  }

  @After
  fun after() {
    aff4ImageStream.close()
  }

  @Test
  fun `open and read bevy source`() {
    createSource().use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("af05fdbda3150e658948ba8b74f1fe82".decodeHex())
      }

      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("86a8ec10b992e4b9236eb4eadca432d5".decodeHex())
      }
    }
  }

  @Test
  fun `open and read multiple times has same read`() {
    createSource().use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("af05fdbda3150e658948ba8b74f1fe82".decodeHex())
      }
    }

    createSource().use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("af05fdbda3150e658948ba8b74f1fe82".decodeHex())
      }
    }
  }

  @Test
  fun `open and read gt chunk size`() {
    createSource().use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize * 2)).isEqualTo(chunkSize * 2)
        assertThat(readSink.size).isEqualTo(chunkSize * 2)
        assertThat(readSink.md5()).isEqualTo("866f93925759a39af236632470789234".decodeHex())
      }
    }
  }

  @Test
  fun `creating sources at location effectively seeks the stream`() {
    createSource(position = chunkSize).use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("86a8ec10b992e4b9236eb4eadca432d5".decodeHex())
      }
    }

    createSource(position = 0).use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("af05fdbda3150e658948ba8b74f1fe82".decodeHex())
      }
    }
  }

  @Test
  fun `open and read skip bytes via buffering`() {
    createSource().buffer().use { imageStreamSource ->
      imageStreamSource.skip(1024)

      Buffer().use { readSink ->
        imageStreamSource.readFully(readSink, chunkSize)
        assertThat(readSink.size).isEqualTo(chunkSize)
        assertThat(readSink.md5()).isEqualTo("fea53f346a83f6fca5d4fa89ac96e758".decodeHex())
      }
    }
  }

  @Test
  fun `reading past end truncates`() {
    createSource(imageStreamConfig.size - 100).use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.read(readSink, chunkSize)).isEqualTo(100)
        assertThat(readSink.size).isEqualTo(100)
        assertThat(readSink.md5()).isEqualTo("6d0bb00954ceb7fbee436bb55a8397a9".decodeHex())
      }
    }
  }

  @Test
  fun `hashes match`() {
    createSource().buffer().use { imageStreamSource ->
      Buffer().use { readSink ->
        assertThat(imageStreamSource.readAll(readSink)).isEqualTo(imageStreamConfig.size)
        assertThat(readSink.md5()).isEqualTo(imageStreamConfig.hash.single { it is Hash.Md5 }.hash)
        assertThat(readSink.sha1()).isEqualTo(imageStreamConfig.hash.single { it is Hash.Sha1 }.hash)
      }
    }
  }

  @Test
  fun `having open sources causes close() to throw`() {
    createSource().use { source ->
      assertThatThrownBy { aff4ImageStream.close() }
        .isInstanceOf(IllegalStateException::class.java)
        .hasMessage("Sources were created and not freed: 1")

      source.close()
      aff4ImageStream.close() // no throw
    }
  }

  private fun createSource(position: Long = 0) = aff4ImageStream.source(position)
}
