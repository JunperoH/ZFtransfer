package io.github.noisetta.nikonpull.ptp

internal fun operationRequestPayload(
    operationCode: Int,
    transactionId: Long,
    parameters: List<Long>,
): ByteArray {
    require(parameters.size <= 5) { "PTP operations support at most five parameters" }
    return LittleEndianWriter().apply {
        // CIPA DC-X005 requires 1 for standard operations with no data-out phase.
        u32(1)
        u16(operationCode)
        u32(transactionId)
        parameters.forEach(::u32)
    }.toByteArray()
}

