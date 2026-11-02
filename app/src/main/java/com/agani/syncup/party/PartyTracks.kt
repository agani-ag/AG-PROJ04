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
 * Where a song's bytes are on this phone: the phone's own file ([local]), or a cache that fills up as
 * pieces arrive from another phone. Reads wait for missing pieces, so a song can start playing (and be
 * passed on) long before all of it is here. Cache files go when the party ends.
 *
 * [pieceSize]: Party's 30 KB messages, or Watch together's bigger streamed pieces. [rolling]: a video can
 * be bigger than the phone's free space, so it's kept in segment files and what's well behind the
 * reading point is deleted; reading back there asks for those pieces again.
 */
internal class TrackData private constructor(
    /** Only for reading a [local] file. */
    private val context: Context?,
    val size: Long,
    private val local: Uri?,
    private val cache: File?,
    val pieceSize: Int = PartyWire.CHUNK,
    private val rolling: Boolean = false,
    private val keepBehind: Long = KEEP_BEHIND,
) {
    private val lock = Object()
    private val have = BitSet()
    private val pieces = if (size <= 0) 0 else ((size + pieceSize - 1) / pieceSize).toInt()
    private var raf: RandomAccessFile? = null
    /** Rolling: one file per [SEG_PIECES] pieces, by segment number. */
    private val segments = HashMap<Int, RandomAccessFile>()
    private val segBytes = SEG_PIECES.toLong() * pieceSize
    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    @Volatile private var closed = false
    /** A read is waiting for this piece (Watch Party asks the host for it). Called on the reading thread. */
    @Volatile var onMissing: ((piece: Int) -> Unit)? = null
    private var lastMissing = -1
    private var lastMissingAt = 0L

    /** Where the player last read (bytes): the guest tells the host, which sends a few minutes past it. */
    @Volatile var lastRead = 0L
        private set

    /** How many pieces the song / video has. */
    val pieceCount: Int get() = pieces

    /** Is this piece here? */
    fun hasPiece(i: Int): Boolean = local != null || synchronized(lock) { have.get(i) }

    /** Every byte is on this phone. */
    val complete: Boolean get() = local != null || synchronized(lock) { have.cardinality() >= pieces }

    /** Which pieces are here (what a guest tells the host after a reconnect). */
    fun pieces(): BitSet = if (local != null) BitSet().apply { set(0, pieces) } else synchronized(lock) { have.clone() as BitSet }

    /** A piece arrived. */
    fun write(offset: Long, data: ByteArray, start: Int, len: Int) {
        if (local != null || closed || offset < 0 || offset >= size || offset % pieceSize != 0L) return
        synchronized(lock) {
            if (closed) return
            val (f, at) = fileFor(offset)
            f.seek(at)
            f.write(data, start, len)
            have.set((offset / pieceSize).toInt())
            lock.notifyAll()
        }
    }

    /** The cache file holding [offset], and where in it. Inside the lock. */
    private fun fileFor(offset: Long): Pair<RandomAccessFile, Long> {
        if (!rolling) return (raf ?: RandomAccessFile(cache, "rw").also { it.setLength(size); raf = it }) to offset
        val seg = (offset / segBytes).toInt()
        val f = segments.getOrPut(seg) { RandomAccessFile(segFile(seg), "rw") }
        return f to offset - seg * segBytes
    }

    private fun segFile(seg: Int) = File(cache!!.parentFile, "${cache.name}.$seg")

    /** Rolling: segments wholly before [position] go, and their pieces count as missing again. Inside the lock. */
    private fun dropBefore(position: Long) {
        if (!rolling || position <= 0) return
        val last = (position / segBytes).toInt() // segments before this one end before position
        segments.keys.filter { it < last }.forEach { seg ->
            runCatching { segments.remove(seg)?.close() }
            segFile(seg).delete()
            have.clear(seg * SEG_PIECES, minOf((seg + 1) * SEG_PIECES, pieces))
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
                    val fd = context?.contentResolver?.openFileDescriptor(uri, "r") ?: return -1
                    pfd = fd
                    FileInputStream(fd.fileDescriptor).also { input = it }
                }
                val n = stream.channel.read(ByteBuffer.wrap(into, off, want), position)
                return if (n < 0) 0 else n
            }
        }
        val piece = (position / pieceSize).toInt()
        lastRead = position
        synchronized(lock) {
            while (!have.get(piece)) {
                if (closed || cancelled()) return -1
                val now = System.currentTimeMillis()
                if (piece != lastMissing || now - lastMissingAt > 1_500) {
                    lastMissing = piece
                    lastMissingAt = now
                    onMissing?.invoke(piece)
                }
                lock.wait(500)
            }
            if (closed) return -1
            // As far as the pieces run in a row from here (and within one segment file).
            var end = minOf(size, have.nextClearBit(piece).toLong() * pieceSize)
            if (rolling) end = minOf(end, (position / segBytes + 1) * segBytes)
            val n = minOf(want.toLong(), end - position).toInt()
            val (f, at) = if (rolling) {
                (segments[(position / segBytes).toInt()] ?: return -1) to position % segBytes
            } else {
                (raf ?: return -1) to position
            }
            f.seek(at)
            f.readFully(into, off, n)
            // What was watched a while ago goes (the player keeps its own buffer ahead of this). Not on a read
            // near the end: a video's index there is read before playing from the start.
            if (position < size - 2 * segBytes) dropBefore(position - keepBehind)
            return n
        }
    }

    /** A whole piece ([pieceSize] bytes, less at the end) from [position]; waits for it. -1 if gone. */
    fun readPiece(position: Long, into: ByteArray, cancelled: () -> Boolean): Int {
        var got = 0
        val want = minOf(pieceSize.toLong(), size - position).toInt()
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
            segments.keys.toList().forEach { seg ->
                runCatching { segments.remove(seg)?.close() }
                segFile(seg).delete()
            }
        }
        cache?.delete()
    }

    companion object {
        /** Rolling: pieces per segment file (16 MB with Watch together's 1 MB pieces). */
        internal const val SEG_PIECES = 16
        /** Rolling: what stays behind the reading point, for a small seek back. */
        const val KEEP_BEHIND = 64L * 1024 * 1024

        /** A song (or video) file on this phone. */
        fun local(context: Context, uri: Uri, size: Long, pieceSize: Int = PartyWire.CHUNK) =
            TrackData(context.applicationContext, size, uri, null, pieceSize)

        /** A song (or video) arriving from another phone, into [folder] of the cache. */
        fun incoming(context: Context, trackId: Int, size: Long, folder: String = "party", pieceSize: Int = PartyWire.CHUNK, rolling: Boolean = false): TrackData =
            incomingIn(File(context.cacheDir, folder), trackId, size, pieceSize, rolling)

        /** The same, into [dir] (tests use a small [keepBehind]). */
        internal fun incomingIn(dir: File, trackId: Int, size: Long, pieceSize: Int, rolling: Boolean, keepBehind: Long = KEEP_BEHIND): TrackData {
            dir.mkdirs()
            return TrackData(null, size, null, File(dir, "track-$trackId-${System.nanoTime()}.part"), pieceSize, rolling, keepBehind)
        }

        /** Left over from a party the app didn't get to close (killed): clear it. */
        fun clearCache(context: Context, folder: String = "party") {
            File(context.cacheDir, folder).listFiles()?.forEach { it.delete() }
        }
    }
}
