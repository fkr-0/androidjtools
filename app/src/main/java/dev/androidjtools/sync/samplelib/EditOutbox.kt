package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibSession

/** Delivers locally queued edits to the server as part of connecting, before the library is pulled. */
interface EditOutbox {
    fun hasPending(): Boolean

    suspend fun deliver(client: SampleLibClient, session: SampleLibSession)

    companion object {
        val None: EditOutbox = object : EditOutbox {
            override fun hasPending(): Boolean = false
            override suspend fun deliver(client: SampleLibClient, session: SampleLibSession) = Unit
        }
    }
}
