package io.github.noisetta.nikonpull.ptp

internal object PtpDataParser {
    fun parseDeviceInfo(data: ByteArray, responderGuid: String = ""): CameraDeviceInfo {
        val reader = LittleEndianReader(data)
        val standardVersion = reader.u16()
        val vendorExtensionId = reader.u32()
        val vendorExtensionVersion = reader.u16()
        val vendorExtensionDescription = reader.ptpString()
        reader.u16() // FunctionalMode
        val operations = reader.u16Array()
        reader.u16Array() // EventsSupported
        reader.u16Array() // DevicePropertiesSupported
        reader.u16Array() // CaptureFormats
        val imageFormats = reader.u16Array()
        val manufacturer = reader.ptpString()
        val model = reader.ptpString()
        val deviceVersion = reader.ptpString()
        val serialNumber = reader.ptpString()
        return CameraDeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            vendorExtensionVersion = vendorExtensionVersion,
            vendorExtensionDescription = vendorExtensionDescription,
            operationsSupported = operations.toSet(),
            imageFormatsSupported = imageFormats.toSet(),
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serialNumber,
            responderGuid = responderGuid,
        )
    }

    fun parseU32Array(data: ByteArray): List<Long> {
        val reader = LittleEndianReader(data)
        val count = reader.u32()
        if (count > MAX_ARRAY_ITEMS || count * 4L > reader.remaining()) {
            throw PtpException("Camera returned an invalid PTP array")
        }
        return List(count.toInt()) { reader.u32() }
    }

    fun parseObjectInfo(handle: Long, data: ByteArray): CameraObject {
        val reader = LittleEndianReader(data)
        val storageId = reader.u32()
        val format = reader.u16()
        reader.u16() // ProtectionStatus
        val objectSize = reader.u32()
        reader.u16() // ThumbFormat
        val thumbnailSize = reader.u32()
        reader.u32() // ThumbPixWidth
        reader.u32() // ThumbPixHeight
        reader.u32() // ImagePixWidth
        reader.u32() // ImagePixHeight
        reader.u32() // ImageBitDepth
        reader.u32() // ParentObject
        reader.u16() // AssociationType
        reader.u32() // AssociationDesc
        reader.u32() // SequenceNumber
        val filename = reader.ptpString()
        val captureDate = if (reader.remaining() > 0) reader.ptpString() else ""

        return CameraObject(
            handle = handle,
            storageId = storageId,
            format = format,
            size = objectSize,
            thumbnailSize = thumbnailSize,
            filename = filename.ifBlank { "DSC_${handle.toString(16)}.JPG" },
            captureDate = captureDate,
        )
    }

    private const val MAX_ARRAY_ITEMS = 100_000L

    private fun LittleEndianReader.u16Array(): List<Int> {
        val count = u32()
        if (count > MAX_ARRAY_ITEMS || count * 2L > remaining()) {
            throw PtpException("Camera returned an invalid PTP array")
        }
        return List(count.toInt()) { u16() }
    }
}
