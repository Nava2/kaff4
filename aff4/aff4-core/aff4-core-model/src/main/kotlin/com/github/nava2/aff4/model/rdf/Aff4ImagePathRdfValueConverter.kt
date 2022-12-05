package com.github.nava2.aff4.model.rdf

import com.github.nava2.aff4.rdf.io.ConcreteRdfValueConverter
import com.github.nava2.guice.typeLiteral
import okio.Path
import okio.Path.Companion.toPath
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Value
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class Aff4ImagePathRdfValueConverter @Inject constructor() :
  ConcreteRdfValueConverter<Path>(typeLiteral<Path>()) {
  override fun parse(value: Value): Path? {
    val path = (value as? Literal)?.label ?: return null
    return path.toPath()
  }

  override fun serialize(value: Path): Value {
    return valueFactory.createLiteral(value.toString())
  }
}
