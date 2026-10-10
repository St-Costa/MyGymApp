package com.mygymapp.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePathPolicyTest {

    @Test
    fun `allows all legitimate record-type paths`() {
        assertTrue(RestorePathPolicy.isAllowedRestorePath("exercises/bench-press-ex-a1b2c3d4.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("exercises/_settings.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("routines/pull-rt-71284f58.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("history/2026/10/2026-10-10_rt-abc12345_deadbeef.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("readiness/abc12345.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("scale/2026/10/2026-10-10.md"))
        assertTrue(RestorePathPolicy.isAllowedRestorePath("ecg/deadbeef.ecg"))
    }

    @Test
    fun `rejects traversal and absolute paths`() {
        assertFalse(RestorePathPolicy.isAllowedRestorePath("../secret"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("history/../../_sync/state.yml"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("/etc/passwd"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("exercises/..\\windows"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("C:/windows/file.md"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath(""))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("   "))
    }

    @Test
    fun `rejects sync state logs caches and derived sidecars`() {
        assertFalse(RestorePathPolicy.isAllowedRestorePath("_sync/state.yml"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("_sync/repo_state.yml"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("logs/app.log"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("cache/images/foo.jpg"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("image_cache/foo"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("history/_idx/ex-abc.idx"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("history/_stats/ex-abc.yaml"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("history/_gitgraph.yaml"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("deleted/something.md"))
    }

    @Test
    fun `rejects wrong extensions and hidden files`() {
        assertFalse(RestorePathPolicy.isAllowedRestorePath("exercises/bench.txt"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("history/2026/10/session.ecg"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("exercises/.hidden.md"))
        assertFalse(RestorePathPolicy.isAllowedRestorePath("random/file.md"))
    }
}
