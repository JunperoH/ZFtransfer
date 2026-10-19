package io.github.noisetta.nikonpull.data

import io.github.noisetta.nikonpull.ptp.CameraObject

/** A handle alone can be reused after a card is formatted or files are deleted. */
fun CameraObject.galleryIdentity(): String = "$storageId|$handle|$filename|$size"

/** Keep unread additions until the user clears them; a reconnect is not a read action. */
fun updatedNewIdentities(
    previous: List<CameraObject>,
    unread: Set<String>,
    current: List<CameraObject>,
    hasPreviousCatalog: Boolean,
): Set<String> {
    val currentIds = current.mapTo(HashSet()) { it.galleryIdentity() }
    val previousIds = previous.mapTo(HashSet()) { it.galleryIdentity() }
    val retained = unread.intersect(currentIds)
    return if (hasPreviousCatalog) retained + (currentIds - previousIds) else retained
}
