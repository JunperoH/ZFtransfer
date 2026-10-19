package io.github.noisetta.nikonpull.data

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryStatusTest {
    private fun photo(handle: Long, name: String) = CameraObject(
        handle = handle,
        storageId = 1,
        format = CameraObject.FORMAT_EXIF_JPEG,
        size = 1024,
        thumbnailSize = 64,
        filename = name,
        captureDate = "20261019T120000",
    )

    @Test fun firstImportDoesNotMarkTheWholeCardAsNew() {
        assertEquals(emptySet<String>(), updatedNewIdentities(emptyList(), emptySet(),
            listOf(photo(1, "A.JPG")), hasPreviousCatalog = false))
    }

    @Test fun additionsStayNewAcrossReconnectUntilAcknowledged() {
        val old = photo(1, "A.JPG")
        val added = photo(2, "B.JPG")
        val first = updatedNewIdentities(listOf(old), emptySet(), listOf(old, added), true)
        assertEquals(setOf(added.galleryIdentity()), first)
        assertEquals(first, updatedNewIdentities(listOf(old, added), first, listOf(old, added), true))
        assertEquals(emptySet<String>(), updatedNewIdentities(listOf(old, added), first, listOf(old), true))
    }

    @Test fun reusedHandleWithDifferentFileIsNew() {
        val previous = photo(7, "OLD.JPG")
        val replacement = photo(7, "NEW.JPG")
        assertEquals(setOf(replacement.galleryIdentity()), updatedNewIdentities(
            listOf(previous), emptySet(), listOf(replacement), true,
        ))
    }
}
