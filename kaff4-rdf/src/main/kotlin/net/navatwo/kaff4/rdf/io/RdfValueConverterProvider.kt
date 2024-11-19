package net.navatwo.kaff4.rdf.io

import jakarta.inject.Inject
import net.navatwo.kaff4.rdf.RdfValueConverter
import java.lang.IllegalArgumentException

internal class RdfValueConverterProvider @Inject constructor(
  private val converters: Set<RdfValueConverter<*>>,
) {
  private val concreteConverters = converters.filterIsInstance<ConcreteRdfValueConverter<*>>()
    .flatMap { c -> c.types.associateWith { c }.entries }
    .associate { it.key.rawType to it.value }

  private val hierarchyConverters = (converters - concreteConverters.values.toSet())
    .flatMap { c -> c.types.associateWith { c }.entries }
    .associate { it.key.rawType to it.value }

  fun getConverter(clazz: Class<*>): RdfValueConverter<*> {
    return tryGetConcrete(clazz)
      ?: tryGetHierarchy(clazz)
      ?: converters.firstOrNull { it.matches(clazz) }
      ?: throw IllegalArgumentException("Could not get converter for class: $clazz")
  }

  private fun tryGetHierarchy(clazz: Class<*>): RdfValueConverter<*>? {
    return hierarchyConverters.entries.firstOrNull { (hierarchyKey, _) ->
      hierarchyKey.isAssignableFrom(clazz)
    }?.value
  }

  private fun tryGetConcrete(clazz: Class<*>) = concreteConverters[clazz]
}
