// Shims so vendored Mihon code compiles without the Metro DI framework.
package dev.zacsweers.metro

import kotlin.reflect.KClass

annotation class Inject
annotation class SingleIn(val scope: KClass<*>)
annotation class ContributesBinding(val scope: KClass<*>)
abstract class AppScope private constructor()
