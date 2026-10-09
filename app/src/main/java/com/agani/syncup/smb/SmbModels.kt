package com.agani.syncup.smb

import java.util.UUID

/** A saved Windows/Samba/NAS share. The share name is required — smbj has no simple way to list a
 * server's shares, so SyncUp asks for it once instead of guessing. */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val lastUsed: Long = 0L,
) {
    val displayPath: String get() = "\\\\$host\\$share"
}

/** One file or folder found while browsing a share, [path] relative to the share's root. */
data class SmbEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/** A server found on the local network (NSD/mDNS, or WS-Discovery for Windows PCs), not yet saved. */
data class FoundHost(val name: String, val host: String, val port: Int)
