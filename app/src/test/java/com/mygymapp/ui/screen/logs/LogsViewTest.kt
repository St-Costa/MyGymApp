package com.mygymapp.ui.screen.logs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the logs viewer mode (second sweep): an open file always wins over the
 * list spinner; the list spinner shows only while loading with nothing open.
 */
class LogsViewTest {

    @Test
    fun `open file wins over loading`() {
        assertEquals(LogsView.FILE, logsViewState(hasOpenFile = true, loading = true))
        assertEquals(LogsView.FILE, logsViewState(hasOpenFile = true, loading = false))
    }

    @Test
    fun `list spinner only while loading with nothing open`() {
        assertEquals(LogsView.LIST_LOADING, logsViewState(hasOpenFile = false, loading = true))
        assertEquals(LogsView.LIST, logsViewState(hasOpenFile = false, loading = false))
    }
}
