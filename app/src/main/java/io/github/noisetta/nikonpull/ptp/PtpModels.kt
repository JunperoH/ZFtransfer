package io.github.noisetta.nikonpull.ptp

data class CameraObject(
    val handle: Long,
    val storageId: Long,
    val format: Int,
    val size: Long,
    val thumbnailSize: Long,
    val filename: String,
    val captureDate: String,
) {
    val isJpeg: Boolean
        get() = format == FORMAT_EXIF_JPEG ||
            filename.endsWith(".jpg", ignoreCase = true) ||
            filename.endsWith(".jpeg", ignoreCase = true)

    val isRaw: Boolean
        get() = RAW_EXTENSIONS.any { filename.endsWith(it, ignoreCase = true) }

    val isOtherPhoto: Boolean
        get() = OTHER_PHOTO_EXTENSIONS.any { filename.endsWith(it, ignoreCase = true) } ||
            format in ADDITIONAL_IMAGE_FORMATS

    val isVideo: Boolean
        get() = VIDEO_EXTENSIONS.any { filename.endsWith(it, ignoreCase = true) } ||
            format in VIDEO_FORMATS

    val isPhoto: Boolean
        get() = isJpeg || isRaw || isOtherPhoto

    val mimeType: String
        get() = when {
            filename.endsWith(".nrw", ignoreCase = true) -> "image/x-nikon-nrw"
            filename.endsWith(".dng", ignoreCase = true) -> "image/dng"
            isRaw -> "image/x-nikon-nef"
            filename.endsWith(".mpo", ignoreCase = true) -> "image/mpo"
            filename.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            filename.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            filename.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            isVideo -> "video/mpeg"
            filename.endsWith(".tif", ignoreCase = true) ||
                filename.endsWith(".tiff", ignoreCase = true) -> "image/tiff"
            else -> "image/jpeg"
        }

    companion object {
        const val FORMAT_EXIF_JPEG = 0x3801
        private val RAW_EXTENSIONS = setOf(".nef", ".nrw", ".dng")
        private val OTHER_PHOTO_EXTENSIONS = setOf(".mpo", ".tif", ".tiff")
        private val VIDEO_EXTENSIONS = setOf(".mov", ".mp4", ".avi", ".mpeg", ".mpg", ".m4v")
        private val ADDITIONAL_IMAGE_FORMATS = setOf(0x3802, 0x380D)
        private val VIDEO_FORMATS = setOf(0x300A, 0x300B, 0x300C, 0x300D)
    }
}

data class CameraDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDescription: String,
    val operationsSupported: Set<Int>,
    val imageFormatsSupported: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val responderGuid: String,
) {
    val displayName: String
        get() {
            val reportedModel = model.trim()
            if (reportedModel.isNotBlank()) {
                return if (manufacturer.contains("Nikon", ignoreCase = true) &&
                    !reportedModel.contains("Nikon", ignoreCase = true)
                ) {
                    "Nikon $reportedModel"
                } else {
                    reportedModel
                }
            }
            return manufacturer.trim().ifBlank { "PTP/IP camera" }
        }

    val missingRequiredOperations: Set<Int>
        get() = REQUIRED_OPERATIONS - operationsSupported

    val isBrowseCompatible: Boolean get() = missingRequiredOperations.isEmpty()
    val supportsThumbnail: Boolean get() = OP_GET_THUMB in operationsSupported
    val supportsPartialObject: Boolean get() = OP_GET_PARTIAL_OBJECT in operationsSupported

    val stableIdentity: String
        get() = listOf(
            manufacturer.trim().lowercase(),
            model.trim().lowercase(),
            serialNumber.trim().ifBlank { responderGuid },
        ).joinToString("|")

    companion object {
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_GET_THUMB = 0x100A
        const val OP_GET_PARTIAL_OBJECT = 0x101B
        val REQUIRED_OPERATIONS = setOf(
            OP_GET_STORAGE_IDS,
            OP_GET_OBJECT_HANDLES,
            OP_GET_OBJECT_INFO,
            OP_GET_OBJECT,
        )
    }
}

class PtpException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class TransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long?,
)
