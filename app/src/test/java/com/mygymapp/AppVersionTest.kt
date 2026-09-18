package com.mygymapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces the versioning policy (docs/CONVENTIONS.md#versioning): `versionCode` tracks the
 * CHANGELOG phase number and `versionName` is `1.<versionCode>` (or `1.<phase>.<hotfix>`
 * for a hotfix inside an already-shipped phase). Runs in the pre-commit test gate, so a
 * release commit that bumps one without the other fails before it lands.
 */
class AppVersionTest {

    @Test
    fun `versionName matches versionCode`() {
        val code = BuildConfig.VERSION_CODE
        val name = BuildConfig.VERSION_NAME
        assertTrue(code > 0)
        val plain = "1.$code"
        val hotfix = Regex("""1\.\d+\.\d+""")
        assertTrue(
            "VERSION_NAME ($name) must be 1.\$VERSION_CODE ($plain) or a hotfix shape 1.<phase>.<n>",
            name == plain || hotfix.matches(name),
        )
    }

    @Test
    fun `versionName is a plain dotted numeric triple at most`() {
        assertTrue(
            BuildConfig.VERSION_NAME.matches(Regex("""1\.\d+(\.\d+)?""")),
        )
        assertEquals(BuildConfig.VERSION_NAME, BuildConfig.VERSION_NAME.trim())
    }
}
