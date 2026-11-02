package com.agani.syncup.stationfiles

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** About 3 minutes: 1, 2, 4, 8, then every 15 s. A song or video waits this long for its station, then pauses. */
private const val FILE_RETRIES = 14

/** Tells AudioSync what a request is for: "play" (a player) or "download" (downloads, Share). */
internal const val USE_HEADER = "X-SyncUp-Use"

/**
 * The players' way to a station file: a [stationUri] becomes the station's address of the moment, with
 * its sign-in, each time the player connects. A song or video carries on after the station gets a new
 * address; its position comes back with a byte range like any seek.
 */
@OptIn(UnstableApi::class)
internal fun stationDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
    ResolvingDataSource.Factory(upstream) { spec ->
        if (parseStationUri(spec.uri.toString()) == null) {
            // A radio on this phone's Wi-Fi plays straight from its PC; anything else as it is.
            Stations.directStream(spec.uri.toString())?.let { spec.withUri(Uri.parse(it)) } ?: spec
        } else {
            val r = Stations.resolve(spec.uri.toString())
            // "play": while its radio is on air, AudioSync lets playing go ahead of downloads (never ahead of the radio).
            spec.withUri(Uri.parse(r.url)).withAdditionalHeaders(r.headers + (USE_HEADER to "play"))
        }
    }

/**
 * The wait before trying a station file again, or null when [info] isn't one. [C.TIME_UNSET] = stop
 * waiting (the file is gone, or the station wants a PIN nobody remembered).
 */
@OptIn(UnstableApi::class)
internal fun stationFileRetryDelay(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long? {
    val key = parseStationUri(info.loadEventInfo.dataSpec.uri.toString())?.first ?: return null
    Stations.markStale() // the next connection looks the station up again: it may have a new address
    val e = info.exception
    if (e is HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode == 401) {
            Stations.tokenRejected(key)
            if (!Stations.hasRememberedPin(key)) return C.TIME_UNSET
        }
        if (e.responseCode == 404 || e.responseCode == 410) return C.TIME_UNSET
    }
    val tries = info.errorCount
    if (tries > FILE_RETRIES) return C.TIME_UNSET
    return minOf(1_000L shl (tries - 1).coerceIn(0, 4), 15_000L)
}

/** For a player that only plays station files (the video player on one): waits for the station, never gives up early. */
@OptIn(UnstableApi::class)
internal class StationFileRetries : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        stationFileRetryDelay(loadErrorInfo) ?: super.getRetryDelayMsFor(loadErrorInfo)

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
}
