package com.mygymapp.data.parser

import com.mygymapp.data.polar.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trip + tolerance coverage for [UserProfileParser] (`exercises/_settings.md`). */
class UserProfileParserTest {

    @Test
    fun `full profile round-trips`() {
        val profile = UserProfile(weightKg = 82.4, isMale = false, heightCm = 168, birthYear = 1990)

        val result = UserProfileParser.fromMarkdown(UserProfileParser.toMarkdown(profile))

        assertEquals(profile, result)
    }

    @Test
    fun `unset birthYear stays absent`() {
        val profile = UserProfile(weightKg = 75.0, isMale = true, heightCm = 175, birthYear = null)

        val markdown = UserProfileParser.toMarkdown(profile)
        val result = UserProfileParser.fromMarkdown(markdown)

        assertEquals(profile, result)
        assertNull(result?.birthYear)
    }

    @Test
    fun `empty file yields null for caller fallback`() {
        assertNull(UserProfileParser.fromMarkdown(""))
        assertNull(UserProfileParser.fromMarkdown("---\n---\n"))
    }

    @Test
    fun `single bad value falls back to that field default`() {
        val markdown = """
            ---
            type: "settings"
            version: 1
            weightKg: "oops"
            isMale: true
            heightCm: 180
            birthYear: 1985
            ---
        """.trimIndent()

        val result = UserProfileParser.fromMarkdown(markdown)

        assertEquals(UserProfile(weightKg = 75.0, isMale = true, heightCm = 180, birthYear = 1985), result)
    }
}
