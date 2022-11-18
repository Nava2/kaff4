import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

dependencies {
  implementation(project(":aff4:aff4-core"))
  implementation(project(":aff4:aff4-rdf:aff4-rdf-memory"))
  implementation(project(":aff4:aff4-compression:aff4-compression-snappy"))

  implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
}

tasks.withType<KotlinCompile> {
  kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}