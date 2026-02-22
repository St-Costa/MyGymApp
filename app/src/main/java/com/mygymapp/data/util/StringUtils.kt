package com.mygymapp.data.util

fun slugify(name: String, id: String): String {
    val slug = name.lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "")
        .replace(Regex("\\s+"), "-")
        .trim('-')
        .take(40)
    return "$slug-$id"
}
