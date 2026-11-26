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
}
