package com.mygymapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [lineDiffStat] — the git-diffstat count shown per changed file in the "Verifica backup
 * sul server" box (docs/BACKUP.md §3.7).
 */
class BackupVerifierDiffStatTest {

    private fun stat(old: String, new: String) = lineDiffStat(old.toByteArray(), new.toByteArray())

    @Test
    fun `brand-new file counts every line as added, none removed`() {
        val (added, removed) = stat("", "---\nid: ex-1\nname: Squat\n---\n")
        assertEquals(0, removed)
        assertEquals(4, added) // 4 newlines
    }

    @Test
    fun `empty new file against empty old is 1 added (never zero for a real push)`() {
        assertEquals(1 to 0, stat("", ""))
    }

    @Test
    fun `a rep-range edit is one line removed and one added`() {
        val old = "id: ex-1\ndefaultRepRangeMin: 15\ndefaultRepRangeMax: 17\n"
        val new = "id: ex-1\ndefaultRepRangeMin: 18\ndefaultRepRangeMax: 17\n"
        assertEquals(1 to 1, stat(old, new))
    }

    @Test
    fun `identical content is zero and zero`() {
        val c = "id: rt-1\nname: Pull\nday: monday\n"
        assertEquals(0 to 0, stat(c, c))
    }

    @Test
    fun `adding a line without removing anything`() {
        val old = "a\nb\n"
        val new = "a\nb\nc\n"
        assertEquals(1 to 0, stat(old, new))
    }

    @Test
    fun `reordering lines is not counted as a change (multiset, not LCS)`() {
        assertEquals(0 to 0, stat("a\nb\nc\n", "c\na\nb\n"))
    }

    @Test
    fun `replacing several lines counts each side`() {
        val old = "h\nx1\nx2\nx3\nt\n"
        val new = "h\ny1\ny2\nt\n"
        assertEquals(2 to 3, stat(old, new)) // y1,y2 added · x1,x2,x3 removed
    }
}
