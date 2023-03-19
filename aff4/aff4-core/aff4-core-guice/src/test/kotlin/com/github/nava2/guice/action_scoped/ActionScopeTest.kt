package com.github.nava2.guice.action_scoped

import com.github.nava2.aff4.containsExactlyInAnyOrderEntriesOf
import com.github.nava2.aff4.isIllegalStateException
import com.github.nava2.aff4.isInstanceOf
import com.github.nava2.guice.KAbstractModule
import com.github.nava2.guice.getInstance
import com.github.nava2.guice.getProvider
import com.github.nava2.guice.key
import com.google.inject.Guice
import com.google.inject.OutOfScopeException
import com.google.inject.ProvisionException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import javax.inject.Inject

internal class ActionScopeTest {
  private val injector = Guice.createInjector(
    ActionScopeModule,
    object : KAbstractModule() {
      override fun configure() {
        bind<SimpleDataClass1>().inActionScope()
      }
    },
  )

  private val actionScope = injector.getInstance<ActionScope>()

  @Test
  fun `seededKeyProvider() immediately throws`() {
    assertThatThrownBy {
      ActionScope.seededKeyProvider(SimpleDataClass1::class).get()
    }.isIllegalStateException()
  }

  @Test
  fun `currentActionKey() throws outside of ActionScope`() {
    assertThatThrownBy {
      actionScope.currentActionKey()
    }.isInstanceOf<OutOfScopeException>()
  }

  @Test
  fun `providers are injected correctly`() {
    val throwingProvider = injector.getProvider<SimpleDataClass1>()
    assertThatThrownBy {
      throwingProvider.get()
    }.hasCauseInstanceOf(OutOfScopeException::class.java)

    val simpleInjectable = SimpleDataClass1()
    actionScope.runInNewScope(
      mapOf(key<SimpleDataClass1>() to simpleInjectable)
    ) {
      val seededProvider = injector.getProvider<SimpleDataClass1>()
      assertThat(seededProvider.get()).isEqualTo(simpleInjectable)
    }
  }

  @Test
  fun `getSeedMap() returns seeded values`() {
    val simpleInjectable = SimpleDataClass1()
    actionScope.runInNewScope(
      mapOf(key<SimpleDataClass1>() to simpleInjectable)
    ) {
      val currentActionKey = actionScope.currentActionKey()
      assertThat(actionScope.getSeedMap(currentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to currentActionKey,
          key<SimpleDataClass1>() to simpleInjectable,
        )
    }
  }

  @Test
  fun `getSeedMap() throws for unknown or finished actions`() {
    val closedActionKey = actionScope.runInNewScope() {
      actionScope.currentActionKey()
    }

    assertThatThrownBy {
      actionScope.getSeedMap(closedActionKey)
    }.isInstanceOf<OutOfScopeException>()
  }

  @Test
  fun `injecting non-scoped values does not update current seed map`() {
    val childInjector = injector.createChildInjector(
      object : KAbstractModule() {
        override fun configure() {
          bind<SimpleDataClass2>()
            .toProvider { SimpleDataClass2("PROVIDER") }
        }
      }
    )

    val actionScope = childInjector.getInstance<ActionScope>()

    val simpleInjectable = SimpleDataClass1()
    actionScope.runInNewScope(
      mapOf(key<SimpleDataClass1>() to simpleInjectable)
    ) {
      val currentActionKey = actionScope.currentActionKey()
      assertThat(actionScope.getSeedMap(currentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to currentActionKey,
          key<SimpleDataClass1>() to simpleInjectable,
        )

      val firstNotScoped = childInjector.getInstance<SimpleDataClass2>()
      val secondNotScoped = childInjector.getInstance<SimpleDataClass2>()

      assertThat(firstNotScoped).isEqualTo(secondNotScoped)

      assertThat(actionScope.getSeedMap(currentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to currentActionKey,
          key<SimpleDataClass1>() to simpleInjectable,
        )
    }
  }

  @Test
  fun `entering multiple scopes does not effect parent scopes`() {
    val childInjector = injector.createChildInjector(
      object : KAbstractModule() {
        override fun configure() {
          bind<SimpleDataClass2>().inActionScope()
        }
      }
    )

    val actionScope = childInjector.getInstance<ActionScope>()

    val parentValue = SimpleDataClass1("P")
    val childValue = SimpleDataClass2("C")
    actionScope.runInNewScope(
      mapOf(key<SimpleDataClass1>() to parentValue)
    ) {
      val parentActionKey = actionScope.currentActionKey()
      assertThat(actionScope.getSeedMap(parentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to parentActionKey,
          key<SimpleDataClass1>() to parentValue,
        )
      assertThat(childInjector.getInstance(key<SimpleDataClass1>())).isEqualTo(parentValue)

      actionScope.runInNewScope(mapOf(key<SimpleDataClass2>() to childValue)) {
        val childActionKey = actionScope.currentActionKey()

        assertThat(actionScope.getSeedMap(childActionKey))
          .containsExactlyInAnyOrderEntriesOf(
            key<ActionScope.ActionKey>() to childActionKey,
            key<SimpleDataClass2>() to childValue,
          )

        assertThat(childInjector.getInstance<SimpleDataClass1>()).isEqualTo(parentValue)
        assertThat(childInjector.getInstance<SimpleDataClass2>()).isEqualTo(childValue)

        assertThat(actionScope.computeFullCurrentSeedMap())
          .containsExactlyInAnyOrderEntriesOf(
            key<ActionScope.ActionKey>() to childActionKey,
            key<SimpleDataClass1>() to parentValue,
            key<SimpleDataClass2>() to childValue,
          )
      }

      // after closing child scope, we have no effects
      assertThat(actionScope.getSeedMap(parentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to parentActionKey,
          key<SimpleDataClass1>() to parentValue,
        )

      assertThat(childInjector.getInstance<SimpleDataClass1>()).isEqualTo(parentValue)
    }
  }

  @Test
  fun `child scopes can override parent scopes with seedMap`() {
    val parentValue = SimpleDataClass1("P")
    val childValue = SimpleDataClass1("C")
    actionScope.runInNewScope(
      mapOf(key<SimpleDataClass1>() to parentValue)
    ) {
      val parentActionKey = actionScope.currentActionKey()
      assertThat(actionScope.getSeedMap(parentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to parentActionKey,
          key<SimpleDataClass1>() to parentValue,
        )
      assertThat(injector.getInstance<SimpleDataClass1>()).isEqualTo(parentValue)

      actionScope.runInNewScope(mapOf(key<SimpleDataClass1>() to childValue)) {
        val childActionKey = actionScope.currentActionKey()

        assertThat(actionScope.getSeedMap(childActionKey))
          .containsExactlyInAnyOrderEntriesOf(
            key<ActionScope.ActionKey>() to childActionKey,
            key<SimpleDataClass1>() to childValue,
          )
        assertThat(injector.getInstance<SimpleDataClass1>()).isEqualTo(childValue)

        assertThat(actionScope.computeFullCurrentSeedMap())
          .containsExactlyInAnyOrderEntriesOf(
            key<ActionScope.ActionKey>() to childActionKey,
            key<SimpleDataClass1>() to childValue,
          )
      }

      // after closing child scope, we have no effects
      assertThat(actionScope.getSeedMap(parentActionKey))
        .containsExactlyInAnyOrderEntriesOf(
          key<ActionScope.ActionKey>() to parentActionKey,
          key<SimpleDataClass1>() to parentValue,
        )
    }
  }

  @Test
  fun `@ActionScoped provider gives current key`() {
    val outOfScopeProvider = injector.getProvider(ActionScope.ActionKey.GUICE_ACTION_KEY)
    assertThatThrownBy { outOfScopeProvider.get() }
      .isInstanceOf<ProvisionException>()
      .hasCauseInstanceOf(OutOfScopeException::class.java)

    actionScope.runInNewScope {
      val expected = actionScope.currentActionKey()
      val provider = injector.getProvider(ActionScope.ActionKey.GUICE_ACTION_KEY)
      assertThat(provider.get())
        .isSameAs(expected)
        .isSameAs(provider.get())
    }
  }

  @Test
  fun `injecting @ActionScoped class works in scope and is singleton`() {
    actionScope.runInNewScope {
      val provider = injector.getProvider<DependsOnActionScoped>()
      assertThat(provider.get().actionScopedValue)
        .isSameAs(provider.get().actionScopedValue)
    }
  }

  @Test
  fun `injecting @ActionScoped class throws out of scope`() {
    assertThatThrownBy {
      injector.getProvider<ActionScopedClass>().get()
    }
      .isInstanceOf<ProvisionException>()
      .hasCauseInstanceOf(OutOfScopeException::class.java)

    assertThatThrownBy {
      injector.getInstance<ActionScopedClass>()
    }
      .isInstanceOf<ProvisionException>()
      .hasCauseInstanceOf(OutOfScopeException::class.java)
  }
}

private data class SimpleDataClass1(val name: String = "A")

private data class SimpleDataClass2(val name: String = "B")

private class DependsOnActionScoped @Inject constructor(
  val actionScopedValue: ActionScopedClass
)

@ActionScoped
private class ActionScopedClass @Inject constructor()
