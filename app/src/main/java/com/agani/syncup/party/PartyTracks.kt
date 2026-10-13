package com.agani.syncup.party

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.BitSet

/** One song in the party, as every phone shows it. [id] is the host's number for it. */
class PartyTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val size: Long,
    val mime: String,
    /** Who put it in the queue (the host's or a guest's name). */
    val addedBy: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("artist", artist).put("ms", durationMs)
        .put("size", size).put("mime", mime).put("by", addedBy)

    companion object {
        fun fromJson(o: JSONObject) = PartyTrack(
            o.optInt("id"), o.optString("title"), o.optString("artist"), o.optLong("ms"),
            o.optLong("size"), o.optString("mime"), o.optString("by"),
        )
    }
}

/**
 * Where a song's bytes are on this phone: the phone's own file ([local]), or a cache file that
 * fills up as pieces arrive from another phone. Reads wait for missing pieces, so a song can start
 * playing (and be passed on) long before all of it is here. Cache files go when the party ends.
 */
internal class TrackData private constructor(
    private val context: Context,
    val size: Long,
    private val local: Uri?,
    private val cache: File?,
) {
    private val lock = Object()
    private val have = BitSet()
    private val pieces = if (size <= 0) 0 else ((size + PartyWire.CHUNK - 1) / PartyWire.CHUNK).toInt()
    private var raf: RandomAccessFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    @Volatile private var closed = false

    /** Every byte is on this phone. */
    val complete: Boolean get() = local != null || synchronized(lock) { have.cardinality() >= pieces }

    /** Which pieces are here (what a guest tells the host after a reconnect). */
    fun pieces(): BitSet = if (local != null) BitSet().apply { set(0, pieces) } else synchronized(lock) { have.clone() as BitSet }

    /** A piece arrived. */
    fun write(offset: Long, data: ByteArray, start: Int, len: Int) {
        if (local != null || closed || offset < 0 || offset >= size || offset % PartyWire.CHUNK != 0L) return
        synchronized(lock) {
            val f = raf ?: RandomAccessFile(cache, "rw").also { it.setLength(size); raf = it }
            f.seek(offset)
            f.write(data, start, len)
            have.set((offset / PartyWire.CHUNK).toInt())
            lock.notifyAll()
        }
    }

    /**
     * Up to [len] bytes from [position] into [into] (blocking until they're here). Returns how
     * many were read, 0 at the end, -1 once closed.
     */
    fun read(position: Long, into: ByteArray, off: Int, len: Int, cancelled: () -> Boolean = { false }): Int {
        if (position >= size) return 0
        val want = minOf(len.toLong(), size - position).toInt()
        val uri = local
        if (uri != null) {
            synchronized(lock) {
                if (closed) return -1
                val stream = input ?: run {
                    val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return -1
                    pfd = fd
                    FileInputStream(fd.fileDescriptor).also { input = it }
                }
                val n = stream.channel.read(ByteBuffer.wrap(into, off, want), position)
                return if (n < 0) 0 else n
            }
        }
        val piece = (position / PartyWire.CHUNK).toInt()
        synchronized(lock) {
            while (!have.get(piece)) {
                if (closed || cancelled()) return -1
                lock.wait(500)
            }
            if (closed) return -1
            // As far as the pieces run in a row from here.
            val end = minOf(size, have.nextClearBit(piece).toLong() * PartyWire.CHUNK)
            val n = minOf(want.toLong(), end - position).toInt()
            val f = raf ?: return -1
            f.seek(position)
            f.readFully(into, off, n)
            return n
        }
    }

    /** A whole piece (CHUNK bytes, less at the end) from [position]; waits for it. -1 if gone. */
    fun readPiece(position: Long, into: ByteArray, cancelled: () -> Boolean): Int {
        var got = 0
        val want = minOf(PartyWire.CHUNK.toLong(), size - position).toInt()
        while (got < want) {
            val n = read(position + got, into, got, want - got, cancelled)
            if (n < 0) return -1
            if (n == 0) break
            got += n
        }
        return got
    }

    /** For the decoder: the song as a seekable source that waits for pieces still on their way. */
    fun dataSource(cancelled: () -> Boolean): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            val n = read(position, buffer, offset, size, cancelled)
            return if (n <= 0) -1 else n
        }

        override fun getSize(): Long = this@TrackData.size
        override fun close() = Unit
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
            runCatching { raf?.close() }
            runCatching { input?.close() }
            runCatching { pfd?.close() }
            raf = null
            input = null
            pfd = null
        }
        cache?.delete()
    }

    companion object {
        /** A song file on this phone. */
        fun local(context: Context, uri: Uri, size: Long) = TrackData(context.applicationContext, size, uri, null)

        /** A song arriving from another phone, into the party cache. */
        fun incoming(context: Context, trackId: Int, size: Long): TrackData {
            val dir = File(context.cacheDir, "party").apply { mkdirs() }
            return TrackData(context.applicationContext, size, null, File(dir, "track-$trackId-${System.nanoTime()}.part"))
        }

        /** Left over from a party the app didn't get to close (killed): clear it. */
        fun clearCache(context: Context) {
            File(context.cacheDir, "party").listFiles()?.forEach { it.delete() }
        }
    }
}
