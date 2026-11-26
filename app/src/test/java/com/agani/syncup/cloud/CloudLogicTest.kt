package com.agani.syncup.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tools → Cloudinary: the phone's own signatures (Everything accounts), file ids, kinds and picture links. */
class CloudLogicTest {
    @Test
    fun signatureMatchesCloudinarysDocumentedExample() {
        val params = mapOf("eager" to "w_400,h_300,c_pad|w_260,h_200,c_crop", "public_id" to "sample_image", "timestamp" to "1315060510")
        assertEquals("bfd09f95f331f558cbd1320e67aa8d488770583e", CloudHttp.sign(params, "abcd"))
    }

    @Test
    fun emptyValuesAreLeftOutOfTheSignature() {
        assertEquals(CloudHttp.sign(mapOf("a" to "1"), "s"), CloudHttp.sign(mapOf("a" to "1", "b" to ""), "s"))
    }

    @Test
    fun newIdsKeepTheFolderAndOnlyOtherFilesKeepTheirExtension() {
        val photo = newPublicId("syncup/accounts/12/Trips", "Beach day.JPG", "image")
        assertTrue(photo, Regex("^syncup/accounts/12/Trips/Beach_day_[0-9a-f]{6}$").matches(photo))
        val doc = newPublicId("syncup/accounts/12", "Plan (v2).PDF", "raw")
        assertTrue(doc, Regex("^syncup/accounts/12/Plan_v2_[0-9a-f]{6}\\.pdf$").matches(doc))
        assertTrue(Regex("^file_[0-9a-f]{6}$").matches(newPublicId("", "", "image")))
    }

    @Test
    fun kinds() {
        assertEquals("image", kindOf("image/heic", "x.heic"))
        assertEquals("video", kindOf("audio/mpeg", "song.mp3"))
        assertEquals("video", kindOf("application/octet-stream", "clip.MP4"))
        assertEquals("raw", kindOf("application/pdf", "a.pdf"))
    }

    @Test
    fun peopleNeverSeeCloudinarysOwnWords() {
        val notAvailable = "Cloud isn't available right now. Please try again later."
        assertEquals(notAvailable, CloudHttp.friendly(401, "unknown api_key"))
        assertEquals(notAvailable, CloudHttp.friendly(400, "Unknown API key 000000000000000"))
        assertEquals(notAvailable, CloudHttp.friendly(401, "Invalid Signature 5e1a. String to sign - 'public_id=x&timestamp=1'."))
        assertEquals("That file is too big for the cloud.", CloudHttp.friendly(400, "File size too large. Got 20000000. Maximum is 10485760."))
        assertEquals("The cloud is busy. Please try again in a few minutes.", CloudHttp.friendly(420, "Rate Limit Exceeded"))
        assertEquals("The cloud is having trouble. Please try again in a little while.", CloudHttp.friendly(502, ""))
        assertEquals("Something went wrong. Please try again.", CloudHttp.friendly(400, "weird"))
    }

    @Test
    fun cloudinaryDates() {
        assertEquals(1764151200000L, isoMillis("2025-11-26T10:00:00Z"))
        assertEquals(0L, isoMillis(null))
    }

    private fun item(type: String, url: String, format: String, w: Int? = 10, h: Int? = 10) =
        CloudItem("syncup/a/x", type, "x.$format", format, 1, w, h, url, 0)

    @Test
    fun pictureLinks() {
        val photo = item("image", "https://res.cloudinary.com/c/image/upload/v1/syncup/a/x.jpg", "jpg")
        assertEquals("https://res.cloudinary.com/c/image/upload/c_fill,w_240,h_240,q_auto,f_auto/v1/syncup/a/x.jpg", photo.thumbUrl)
        assertEquals("https://res.cloudinary.com/c/image/upload/c_limit,w_2000,h_2000,q_auto,f_auto/v1/syncup/a/x.jpg", photo.viewUrl)

        val video = item("video", "https://res.cloudinary.com/c/video/upload/v1/syncup/a/x.mp4", "mp4")
        assertTrue(video.isVideo)
        assertEquals("https://res.cloudinary.com/c/video/upload/c_fill,w_240,h_240,so_0,q_auto/v1/syncup/a/x.jpg", video.thumbUrl)

        val song = item("video", "https://res.cloudinary.com/c/video/upload/v1/syncup/a/x.mp3", "mp3", null, null)
        assertTrue(song.isAudio)
        assertFalse(song.isVideo)
        assertNull(song.thumbUrl)

        val pdf = item("raw", "https://res.cloudinary.com/c/raw/upload/v1/syncup/a/x.pdf", "pdf")
        assertEquals("https://res.cloudinary.com/c/image/upload/c_fill,w_240,h_240,pg_1,q_auto/v1/syncup/a/x.jpg", pdf.thumbUrl)
        assertNull(item("raw", "https://res.cloudinary.com/c/raw/upload/v1/syncup/a/x.zip", "zip").thumbUrl)
    }

    @Test
    fun stationFoldersBecomeCloudFolders() {
        assertEquals("Music/Live", cloudFolderPath("Music/Live"))
        assertEquals("Rock _ Roll/Best_ 2026", cloudFolderPath("Rock & Roll/Best: 2026"))
        assertEquals("பாடல்கள்/Mix (1)", cloudFolderPath("/பாடல்கள்//Mix (1)/"))
        assertEquals("a/b", cloudFolderPath("a/../b/."))
        assertEquals("", cloudFolderPath(""))
        assertEquals(10, cloudFolderPath((1..14).joinToString("/") { "f$it" }).split('/').size)
    }

    private fun file(pid: String, name: String = "$pid.jpg") = CloudItem(pid, "image", name, "jpg", 1, 10, 10, "https://x/$pid.jpg", 0)

    @Test
    fun ownFolderShowsWhatCloudinaryHasThere() {
        val root = "syncup/accounts/23"
        val found = listOf(
            Found(root, file("kq3x9", "Beach.jpg")),                  // put in on Cloudinary's website
            Found(root, file("app1", "app1_ab12cd.jpg")),             // uploaded in the app: on the server's list
            Found("$root/Trips", file("moved1", "moved1_x.jpg")),     // the app's file, moved on Cloudinary's website
            Found("$root/Trips/2026", file("deep1")),                 // a subfolder only Cloudinary has
            Found(root, file("gone1")),                               // deleted on this phone a moment ago
        )
        val listed = mapOf("app1" to "Photo 1.jpg", "moved1" to "Hills.jpg", "new1" to "Just now.jpg")
        val server = listOf(file("app1", "Photo 1.jpg"), file("moved1", "Hills.jpg"), file("new1", "Just now.jpg"))

        val (folders, files) = mergeFolder(root, listOf("Empty"), server, found, listed, gone = setOf("gone1"))
        assertEquals(listOf("Empty", "Trips"), folders)
        // The app's files keep their names and can be changed; one just uploaded (Cloudinary's search hasn't it yet)
        // stays; the one moved away doesn't show here; the one put in outside SyncUp shows, read-only.
        assertEquals(listOf("app1" to false, "new1" to false, "kq3x9" to true), files.map { it.publicId to it.readOnly })
        assertEquals("Beach.jpg", files.last().name)

        val (sub, inTrips) = mergeFolder("$root/Trips", emptyList(), emptyList(), found, listed, emptySet())
        assertEquals(listOf("2026"), sub)
        // Moved here on Cloudinary's website: shown here with its name from the server's list, and still changeable.
        assertEquals(listOf(Triple("moved1", "Hills.jpg", false)), inTrips.map { Triple(it.publicId, it.name, it.readOnly) })
    }

    @Test
    fun folderCountsHoldEverythingInsideOnce() {
        val root = "syncup/accounts/23"
        val found = listOf(
            Found(root, file("top")),                          // in the folder itself: no subfolder counts it
            Found("$root/Trips", file("t1")), Found("$root/Trips/2026", file("t2")), Found("$root/Trips/2026", file("t2")),
            Found("$root/Work", file("w1")), Found("$root/Work", file("w2")),
            Found("syncup/accounts/230/x", file("other")),     // another person's folder (a longer number)
        )
        assertEquals(mapOf("$root/Trips" to 2, "$root/Work" to 1), folderCounts(root, found, gone = setOf("w2")))
        assertEquals(mapOf("syncup" to 6), folderCounts("", found))  // the whole cloud; t2 once
    }

    @Test
    fun kindsForTheFilter() {
        assertEquals("photos", item("image", "u", "jpg").kind)
        assertEquals("documents", item("image", "u", "pdf").kind)  // Cloudinary keeps PDFs as images
        assertEquals("videos", item("video", "u", "mp4").kind)
        assertEquals("audio", item("video", "u", "mp3", null, null).kind)
        assertEquals("documents", item("raw", "u", "docx").kind)
        assertEquals("other", item("raw", "u", "zip").kind)
    }

    @Test
    fun searchLinkSignatureMatchesCloudinarysSdk() {
        // Cloudinary's Python SDK test (test_search.py): secret "secret", ttl 300.
        val b64 = "eyJleHByZXNzaW9uIjoicmVzb3VyY2VfdHlwZTppbWFnZSBBTkQgdGFncz1raXR0ZW4gQU5EIHVwbG9hZGVkX2F0" +
            "PjFkIEFORCBieXRlcz4xbSIsIm1heF9yZXN1bHRzIjozMCwic29ydF9ieSI6W3sicHVibGljX2lkIjoiZGVzYyJ9XX0="
        assertEquals("431454b74cefa342e2f03e2d589b2e901babb8db6e6b149abf25bc0dd7ab20b7", CloudHttp.searchSignature(300, b64, "secret"))
        assertEquals("my\\ files/a\\:b", searchTerm("my files/a:b"))
    }
}
