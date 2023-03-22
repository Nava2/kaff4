package com.github.nava2.aff4

import com.github.nava2.aff4.streams.compression.Aff4SnappyPlugin

val Aff4ImageTestModule.Companion.BaseLinear: Aff4ImageTestModule
  get() = object : Aff4ImageTestModule(imageName = "Base-Linear.aff4") {
    override fun configureOther() {
      install(Aff4SnappyPlugin)
    }
  }

val Aff4ImageTestModule.Companion.BaseLinearStriped: Aff4ImageTestModule
  get() = object : Aff4ImageTestModule(imageName = "base-linear_striped/Base-Linear_2.aff4") {
    override fun configureOther() {
      install(Aff4SnappyPlugin)
    }
  }
