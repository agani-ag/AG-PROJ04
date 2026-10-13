package com.agani.syncup.watchparty

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.agani.syncup.party.TrackData
import java.io.IOException

/**
 * The player reads a guest's video from [TrackData] — the pieces arriving from the host. A read
 * waits for a missing piece (and asks the host for it first, via [TrackData.onMissing]).
 */
@UnstableApi
internal class PieceDataSource(private val data: TrackData) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var position = 0L
    private var left = 0L
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position
        if (position > data.size) throw IOException("Past the end")
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else data.size - position
        closed = false
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        val n = data.read(position, buffer, offset, minOf(length.toLong(), left).toInt()) { closed }
        if (n < 0) throw IOException("Watch Party ended")
        if (n == 0) return C.RESULT_END_OF_INPUT
        position += n
        left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            closed = true
            uri = null
            transferEnded()
        }
    }

    class Factory(private val data: TrackData) : DataSource.Factory {
        override fun createDataSource(): DataSource = PieceDataSource(data)
    }
}

/**
 * A guest's video comes from the host's pieces ("syncup-watch://…"); anything else — a subtitles file
 * this person picked — from the phone ([other]). Lets the player load both through one factory.
 */
@UnstableApi
internal class RoutingDataSource(private val pieces: DataSource, private val other: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        pieces.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val d = if (dataSpec.uri.scheme == "syncup-watch") pieces else other
        current = d
        return d.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        current?.close()
        current = null
    }

    class Factory(private val pieces: DataSource.Factory, private val other: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(pieces.createDataSource(), other.createDataSource())
    }
}
