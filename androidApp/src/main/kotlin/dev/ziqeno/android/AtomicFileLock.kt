package dev.ziqeno.android

import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile

private val fileAccess = Any()

// Framework AtomicFile does not synchronize readers and writers across processes.
internal fun <T> AtomicFile.locked(action: () -> T): T = synchronized(fileAccess) {
    val lock = File(baseFile.parentFile, "${baseFile.name}.lock")
    RandomAccessFile(lock, "rw").use { handle ->
        handle.channel.lock().use { action() }
    }
}
