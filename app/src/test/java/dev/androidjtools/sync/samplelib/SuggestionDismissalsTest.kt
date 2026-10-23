package dev.androidjtools.sync.samplelib

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuggestionDismissalsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FileSuggestionDismissals(File(tmp.root, "dismissed.json"))

    @Test
    fun aDismissalIsKeyedByIdAndRevisionSoAReplacementReappears() {
        val d = InMemorySuggestionDismissals()
        d.dismiss("run-1:tempo:a1", "rev-1")

        assertTrue(d.isDismissed("run-1:tempo:a1", "rev-1"))
        assertFalse("same id, new revision", d.isDismissed("run-1:tempo:a1", "rev-2"))
        assertFalse("new run, new id", d.isDismissed("run-2:tempo:a1", "rev-1"))
    }

    @Test
    fun dismissalsSurviveARestart() {
        store().apply { ensureLoaded(); dismiss("s1", "r1"); dismiss("s2", "r9") }

        val restarted = store().apply { ensureLoaded() }
        assertTrue(restarted.isDismissed("s1", "r1"))
        assertTrue(restarted.isDismissed("s2", "r9"))
        assertFalse(restarted.isDismissed("s1", "r2"))
    }

    @Test
    fun clearForgetsEverythingOnDiskToo() {
        val d = store().apply { ensureLoaded(); dismiss("s1", "r1") }
        d.clear()

        assertTrue(d.keys.value.isEmpty())
        assertTrue(store().apply { ensureLoaded() }.keys.value.isEmpty())
    }

    @Test
    fun anUnreadableFileMeansNothingIsDismissedNotACrash() {
        File(tmp.root, "dismissed.json").writeText("not json")
        val d = store().apply { ensureLoaded() }

        assertTrue(d.keys.value.isEmpty())
        d.dismiss("s1", "r1")
        assertTrue(store().apply { ensureLoaded() }.isDismissed("s1", "r1"))
    }

    @Test
    fun theListIsBoundedAndKeepsTheNewest() {
        val d = store().apply { ensureLoaded() }
        repeat(2_100) { d.dismiss("s$it", "r") }

        assertTrue(d.keys.value.size <= 2_000)
        assertTrue(d.isDismissed("s2099", "r"))
        assertFalse(d.isDismissed("s0", "r"))
    }

    @Test
    fun aFailedWriteStillHidesTheSuggestionForThisRun() {
        val blocked = File(tmp.root, "blocked").apply { writeText("a file where a directory is needed") }
        val d = FileSuggestionDismissals(File(blocked, "dismissed.json"))
        d.ensureLoaded()
        d.dismiss("s1", "r1")

        assertTrue(d.isDismissed("s1", "r1"))
        assertEquals(1, d.keys.value.size)
    }
}
