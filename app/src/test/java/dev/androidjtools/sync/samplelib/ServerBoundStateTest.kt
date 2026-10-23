package dev.androidjtools.sync.samplelib

import dev.androidjtools.sync.journal.DurableMutationJournal
import dev.androidjtools.sync.journal.JournalSnapshot
import dev.androidjtools.sync.journal.MutationJournalStorage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBoundStateTest {
    private class Storage(var failStores: Boolean = false) : MutationJournalStorage {
        var durable = JournalSnapshot()
        override fun load() = durable
        override fun store(snapshot: JournalSnapshot) {
            if (failStores) throw IOException("disk")
            durable = snapshot
        }
    }

    private fun journalWithBothFamilies(storage: Storage): DurableMutationJournal = DurableMutationJournal(storage).also {
        it.enqueue("mutation-edit-1", "interval", "iv", "r", "interval.editor_state.replace", """{"start_ms":0,"end_ms":5,"markers":[]}""", "{}", 1L)
        it.enqueue("mutation-accept-1", "analysis_suggestion", "s", "r", "analysis_suggestion.accept", """{"target_interval_id":"iv"}""", "{}", 2L)
    }

    @Test
    fun forgetOrSwitchingServerDropsEditsOfBothFamiliesAndEveryDismissal() {
        val journal = journalWithBothFamilies(Storage())
        val dismissals = InMemorySuggestionDismissals().apply { dismiss("s", "r") }

        assertTrue(discardServerBoundState(lazyOf(journal), dismissals))

        assertTrue(journal.all().isEmpty())
        assertTrue(dismissals.keys.value.isEmpty())
    }

    @Test
    fun aJournalThatCannotBeClearedReportsFailureSoTheCallerKeepsTheConnection() {
        val storage = Storage()
        val journal = journalWithBothFamilies(storage)
        val dismissals = InMemorySuggestionDismissals().apply { dismiss("s", "r") }
        storage.failStores = true

        assertFalse(discardServerBoundState(lazyOf(journal), dismissals))
        assertEquals(2, journal.all().size)
    }
}
