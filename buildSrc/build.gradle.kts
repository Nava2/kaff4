plugins {
  `kotlin-dsl`
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20")
  implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.5")
  implementation("com.jaredsburrows:gradle-license-plugin:0.9.3")
}
