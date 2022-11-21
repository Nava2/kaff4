package com.github.nava2.aff4.streams.symbolics

import com.github.nava2.aff4.Aff4ImageTestModule
import com.github.nava2.aff4.BaseLinear
import com.github.nava2.aff4.io.limit
import com.github.nava2.aff4.io.repeatByteString
import com.github.nava2.aff4.io.use
import com.github.nava2.aff4.model.rdf.createAff4Iri
import com.github.nava2.test.GuiceModule
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.rdf4j.model.ValueFactory
import org.junit.jupiter.api.Test
import javax.inject.Inject

class SymbolicsTest {
  @GuiceModule
  val modules = Aff4ImageTestModule.BaseLinear

  @Inject
  private lateinit var symbolics: Symbolics

  @Inject
  private lateinit var valueFactory: ValueFactory

  @Test
  fun `expect zero providers are same`() {
    val provider0 = symbolics.provider(0)
    val providerZero = symbolics.provider(valueFactory.createAff4Iri("Zero"))

    assertThat(provider0).isSameAs(providerZero)

    providerZero.limit(10).use { s ->
      Buffer().use { buffer ->
        assertThat(s.read(buffer, 20)).isEqualTo(10)
        assertThat(buffer.readByteString()).isEqualTo(0.repeatByteString(10))
      }
    }
  }

  @Test
  fun `expect providers are singleton`() {
    assertThat(symbolics.provider(0xAA.toByte()))
      .isSameAs(symbolics.provider(0xAA.toByte()))
      .isSameAs(symbolics.provider(valueFactory.createAff4Iri("SymbolicStreamAA")))
  }

  @Test
  fun `expect provider have correct casing`() {
    // Zero is special :)
    assertThat(symbolics.provider(0.toByte()).arn)
      .isEqualTo(valueFactory.createAff4Iri("Zero"))
    assertThat(symbolics.provider(valueFactory.createAff4Iri("Zero")).arn)
      .isEqualTo(valueFactory.createAff4Iri("Zero"))

    assertThat(symbolics.provider(0xAA.toByte()).arn)
      .isEqualTo(valueFactory.createAff4Iri("SymbolicStreamAA"))

    assertThat(symbolics.provider(valueFactory.createAff4Iri("UnreadableData")).arn)
      .isEqualTo(valueFactory.createAff4Iri("UnreadableData"))
  }
}
