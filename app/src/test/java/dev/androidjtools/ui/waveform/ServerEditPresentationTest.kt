package dev.androidjtools.ui.waveform

import dev.androidjtools.core.provider.ServerEdit
import dev.androidjtools.core.provider.ServerEditStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEditPresentationTest {
    @Test
    fun everyStateHasItsOwnWords() {
        val labels = ServerEditStatus.entries.map(::serverEditStatusLabel)
        assertEquals(ServerEditStatus.entries.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun aPendingOrSendingEditIsNeverPresentedAsSaved() {
        listOf(ServerEditStatus.PENDING, ServerEditStatus.SENDING, ServerEditStatus.CONFLICT, ServerEditStatus.REJECTED).forEach {
            assertFalse("$it must not read as saved", serverEditStatusLabel(it).contains("Saved", ignoreCase = true))
        }
        assertTrue(serverEditStatusLabel(ServerEditStatus.SAVED).contains("Saved"))
    }

    private fun edit(id: String, status: ServerEditStatus, track: String? = "t1") =
        ServerEdit(id, "iv-1", track, "Cue edit", status, "", 0L)

    @Test
    fun onlyThisTracksEditsAreShownAndEveryUnfinishedOneIsKept() {
        val all = listOf(
            edit("e5", ServerEditStatus.CONFLICT),
            edit("e4", ServerEditStatus.PENDING, track = "other"),
            edit("e3", ServerEditStatus.PENDING),
            edit("e2", ServerEditStatus.REJECTED),
            edit("e1", ServerEditStatus.SENDING),
        )
        assertEquals(listOf("e5", "e3", "e2", "e1"), visibleServerEdits(all, "t1").map { it.mutationId })
    }

    @Test
    fun savedEditsAreCappedToTheNewestFewButNeverHideUnfinishedOnes() {
        val all = listOf(
            edit("s5", ServerEditStatus.SAVED),
            edit("p4", ServerEditStatus.PENDING),
            edit("s3", ServerEditStatus.SAVED),
            edit("s2", ServerEditStatus.SAVED),
            edit("s1", ServerEditStatus.SAVED),
        )
        assertEquals(listOf("s5", "p4", "s3", "s2"), visibleServerEdits(all, "t1", savedLimit = 3).map { it.mutationId })
    }
}
