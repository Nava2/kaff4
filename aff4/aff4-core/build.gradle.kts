dependencies {
  implementation(kotlin("reflect"))

  api(Dependencies.OKIO)

  api(project(":aff4:aff4-core:aff4-core-logging"))
  api(project(":aff4:aff4-core:aff4-core-guice"))
  api(project(":aff4:aff4-core:aff4-core-model"))

  implementation(project(":aff4:aff4-rdf"))

  implementation(project(":aff4:aff4-core:aff4-core-okio"))
  implementation(project(":aff4:aff4-core:aff4-core-interval-tree"))

  implementation(Dependencies.APACHE_COMMONS_LANG)
  implementation(Dependencies.GUICE_ASSISTED_INJECT)
  implementation(Dependencies.CAFFIENE)

  implementation("io.github.zabuzard.fastcdc4j:fastcdc4j:1.3")

  testImplementation(project(":aff4:aff4-compression-snappy"))
  testImplementation(project(":aff4:aff4-core:aff4-core-test"))
  testImplementation(project(":aff4:aff4-rdf:aff4-rdf-memory"))
}