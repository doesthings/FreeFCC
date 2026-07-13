package com.freefcc.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers DumplBuilder.validateResponse() against the checklist Codex required in
 * plan review: valid response, CRC-8, CRC-16, length, sequence, routing, command
 * set/ID, and the response bit — each isolated so only that one check fails.
 */
class DumplResponseValidationTest {

    private fun buildRawFrame(
        sender: Int,
        dst: Int,
        seq: Int,
        cmdType: Int,
        cmdSet: Int,
        cmdId: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = payload.size + 13
        val out = ByteArray(totalLength)
        out[0] = 0x55
        out[1] = (totalLength and 0xFF).toByte()
        out[2] = (((totalLength shr 8) and 0x03) or 0x04).toByte()
        out[3] = DumplBuilder.crc8(out, 0, 3).toByte()
        out[4] = sender.toByte()
        out[5] = dst.toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = ((seq shr 8) and 0xFF).toByte()
        out[8] = cmdType.toByte()
        out[9] = cmdSet.toByte()
        out[10] = cmdId.toByte()
        System.arraycopy(payload, 0, out, 11, payload.size)
        val crc = DumplBuilder.crc16(out, 0, 11 + payload.size)
        out[totalLength - 2] = (crc and 0xFF).toByte()
        out[totalLength - 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }

    private val request = buildRawFrame(
        sender = 0x01, dst = 0x28, seq = 1234,
        cmdType = 0x00, cmdSet = 0x02, cmdId = 0x03,
        payload = byteArrayOf(1, 2, 3)
    )

    private fun validResponse(payload: ByteArray = byteArrayOf(9, 8, 7, 6)) = buildRawFrame(
        sender = 0x28, dst = 0x01, seq = 1234,
        cmdType = 0x80.toByte().toInt() and 0xFF, cmdSet = 0x02, cmdId = 0x03,
        payload = payload
    )

    @Test
    fun `valid response yields payload`() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val response = validResponse(payload)
        assertArrayEquals(payload, DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `crc8 mismatch is rejected`() {
        val response = validResponse()
        response[3] = (response[3] + 1).toByte()
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `crc16 mismatch is rejected`() {
        val response = validResponse()
        response[response.size - 1] = (response[response.size - 1] + 1).toByte()
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `length mismatch is rejected`() {
        val response = validResponse() + byteArrayOf(0x00)
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `sequence mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 9999,
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `routing mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x01, dst = 0x28, seq = 1234, // not reversed
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `command set mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x80, cmdSet = 0x99, cmdId = 0x03,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `command id mismatch is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x80, cmdSet = 0x02, cmdId = 0x99,
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumplBuilder.validateResponse(request, response))
    }

    @Test
    fun `missing response bit is rejected`() {
        val response = buildRawFrame(
            sender = 0x28, dst = 0x01, seq = 1234,
            cmdType = 0x00, cmdSet = 0x02, cmdId = 0x03, // response bit not set
            payload = byteArrayOf(9, 8, 7, 6)
        )
        assertNull(DumplBuilder.validateResponse(request, response))
    }
}
