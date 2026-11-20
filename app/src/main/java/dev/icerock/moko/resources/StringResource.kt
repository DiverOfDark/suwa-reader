// Minimal stand-in for moko-resources used by vendored Mihon code: strings are English-only literals.
package dev.icerock.moko.resources

class StringResource(val text: String)

class PluralsResource(val one: String, val other: String) {
    fun get(count: Int): String = if (count == 1) one else other
}
