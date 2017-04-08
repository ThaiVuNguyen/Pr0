package com.pr0gramm.app.services

import android.content.Context
import com.google.common.primitives.UnsignedBytes
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.util.AndroidUtility.doInBackground
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Very simple service to check if an item was already visited or not.
 */
@Singleton
class SeenService @Inject constructor(context: Context) {
    private val lock = Any()
    private val buffer = SettableFuture.create<ByteBuffer>()

    init {
        doInBackground {
            try {
                val file = File(context.filesDir, "seen-posts.bits")
                buffer.set(mapByteBuffer(file))
            } catch (error: IOException) {
                logger.warn("Could not load the seen-Cache")
            }
        }
    }

    fun isSeen(item: FeedItem): Boolean {
        return isSeen(item.id())
    }

    fun isSeen(id: Long): Boolean {
        if (!this.buffer.isDone)
            return false

        val idx = id.toInt() / 8

        val buffer = Futures.getUnchecked(this.buffer)
        if (idx < 0 || idx >= buffer.limit()) {
            logger.warn("Id is too large")
            return false
        }

        val mask = 1 shl (7 - id % 8).toInt()
        return (UnsignedBytes.toInt(buffer.get(idx)) and mask) != 0
    }

    fun markAsSeen(item: FeedItem) {
        markAsSeen(item.id().toInt())
    }

    fun markAsSeen(id: Int) {
        if (!this.buffer.isDone)
            return

        val idx = id / 8

        val buffer = Futures.getUnchecked(this.buffer)
        if (idx < 0 || idx >= buffer.limit()) {
            logger.warn("Id is too large")
            return
        }

        // only one thread can write the buffer at a time.
        synchronized(lock) {
            var value = UnsignedBytes.toInt(buffer.get(idx))
            value = value or (1 shl (7 - id % 8))
            buffer.put(idx, UnsignedBytes.saturatedCast(value.toLong()))
        }
    }

    /**
     * Removes the "marked as seen" status from all items.
     */
    fun clear() {
        if (!this.buffer.isDone)
            return

        val buffer = Futures.getUnchecked(this.buffer)

        synchronized(lock) {
            logger.info("Removing all the items")
            for (idx in 0..buffer.limit() - 1) {
                buffer.put(idx, 0.toByte())
            }
        }
    }

    /**
     * Maps the cache into a byte buffer. The buffer is backed by the file, so
     * all changes to the buffer are written back to the file.

     * @param file The file to map into memory
     */
    @Throws(IOException::class)
    private fun mapByteBuffer(file: File): ByteBuffer {
        // space for up to four million posts
        val size = (4000000 / 8).toLong()

        logger.info("Mapping cache: " + file)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size)
            return raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("SeenService")
    }
}