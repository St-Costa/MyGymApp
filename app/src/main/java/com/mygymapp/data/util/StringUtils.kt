package com.mygymapp.data.util

private val NON_SLUG_CHARS = Regex("[^a-z0-9\\s-]")
private val WHITESPACE = Regex("\\s+")

fun slugify(name: String, id: String): String {
    val slug = name.lowercase()
        .replace(NON_SLUG_CHARS, "")
        .replace(WHITESPACE, "-")
        .trim('-')
        .take(40)
    return "$slug-$id"
}
