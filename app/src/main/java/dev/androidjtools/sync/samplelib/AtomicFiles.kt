package dev.androidjtools.sync.samplelib

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    /** Writes [bytes] to a temp file in the same directory, fsyncs it, then atomically replaces [target]. */
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("no parent directory for $target")
        dir.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IOException("atomic replace is not supported for $target", error)
            }
        } finally {
            temp.delete()
        }
    }
}
