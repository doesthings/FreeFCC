package com.freefcc.app

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A single DUMPL command frame, before wire-encoding.
 *
 * DUMPL is DJI's internal command protocol used over a local TCP proxy
 * (127.0.0.1:40009) on their controllers. Each frame is a self-contained
 * binary command with CRC checks.
 *
 * @property sender   Sender byte — low 5 bits = device type, high 3 bits = index
 * @property cmdType  Command type byte — bit 7 = packet type (0=Request, 1=Response),
 *                     bits 5-6 = ack type, bits 0-2 = encryption
 * @property cmdSet   Command set (groups of related commands — see CMD_SET_TYPE)
 * @property cmdId    Command ID within the set
 * @property dst      Destination byte — low 5 bits = device type, high 3 bits = index
 * @property payload  Raw payload bytes
 */
data class DumplFrame(
    val sender: Int,
    val cmdType: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val dst: Int,
    val payload: ByteArray
)

/**
 * Builds wire-format DUMPL frames from structured input.
 *
 * Wire format (little-endian, 0x55 packet type):
 *   Byte  0       0x55 (magic)
 *   Byte  1-2     Length (bits 0-9) + version (bits 10-15), version is always 1
 *   Byte  3       CRC-8 of bytes 0-2 (polynomial 0x8C reflected, init 0x77)
 *   Byte  4       Sender (type + index)
 *   Byte  5       Receiver (type + index)
 *   Byte  6-7     Sequence number (LE)
 *   Byte  8       Command type (packet_type + ack_type + encrypt_type)
 *   Byte  9       Command set
 *   Byte  10      Command ID
 *   Byte  11..N   Payload
 *   Byte  N+1..N+2  CRC-16 of bytes 0..N (polynomial 0x1021 reflected, init 0x3692)
 *
 * Reference: https://github.com/o-gs/dji-firmware-tools/blob/master/comm_dat2pcap.py
 */
class DumplBuilder {

    private var sequenceNumber: Int = 4096

    /** Builds a single DUMPL frame as a byte array, ready to send over TCP. */
    fun buildFrame(frame: DumplFrame): ByteArray {
        val payload = frame.payload
        val totalLength = payload.size + 13 // 11 header + payload + 2 CRC

        require(totalLength <= 1023) { "Frame too long: $totalLength bytes (max 1023)" }

        val out = ByteArray(totalLength)

        // Header
        out[0] = 0x55
        out[1] = (totalLength and 0xFF).toByte()
        out[2] = (((totalLength shr 8) and 0x03) or 0x04).toByte() // version=1 in bits 10-15
        out[3] = crc8(out, 0, 3).toByte()

        // Routing
        out[4] = frame.sender.toByte()
        out[5] = frame.dst.toByte()
        out[6] = (sequenceNumber and 0xFF).toByte()
        out[7] = ((sequenceNumber shr 8) and 0xFF).toByte()

        // Command
        out[8] = frame.cmdType.toByte()
        out[9] = frame.cmdSet.toByte()
        out[10] = frame.cmdId.toByte()

        // Payload
        System.arraycopy(payload, 0, out, 11, payload.size)

        // CRC-16
        val crc = crc16(out, 0, 11 + payload.size)
        out[totalLength - 2] = (crc and 0xFF).toByte()
        out[totalLength - 1] = ((crc shr 8) and 0xFF).toByte()

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return out
    }

    companion object {

        // CRC-8 table — polynomial 0x8C (reflected), 256 entries
        private val CRC8_TABLE = intArrayOf(
            0x00,0x5E,0xBC,0xE2,0x61,0x3F,0xDD,0x83,0xC2,0x9C,0x7E,0x20,0xA3,0xFD,0x1F,0x41,
            0x9D,0xC3,0x21,0x7F,0xFC,0xA2,0x40,0x1E,0x5F,0x01,0xE3,0xBD,0x3E,0x60,0x82,0xDC,
            0x23,0x7D,0x9F,0xC1,0x42,0x1C,0xFE,0xA0,0xE1,0xBF,0x5D,0x03,0x80,0xDE,0x3C,0x62,
            0xBE,0xE0,0x02,0x5C,0xDF,0x81,0x63,0x3D,0x7C,0x22,0xC0,0x9E,0x1D,0x43,0xA1,0xFF,
            0x46,0x18,0xFA,0xA4,0x27,0x79,0x9B,0xC5,0x84,0xDA,0x38,0x66,0xE5,0xBB,0x59,0x07,
            0xDB,0x85,0x67,0x39,0xBA,0xE4,0x06,0x58,0x19,0x47,0xA5,0xFB,0x78,0x26,0xC4,0x9A,
            0x65,0x3B,0xD9,0x87,0x04,0x5A,0xB8,0xE6,0xA7,0xF9,0x1B,0x45,0xC6,0x98,0x7A,0x24,
            0xF8,0xA6,0x44,0x1A,0x99,0xC7,0x25,0x7B,0x3A,0x64,0x86,0xD8,0x5B,0x05,0xE7,0xB9,
            0x8C,0xD2,0x30,0x6E,0xED,0xB3,0x51,0x0F,0x4E,0x10,0xF2,0xAC,0x2F,0x71,0x93,0xCD,
            0x11,0x4F,0xAD,0xF3,0x70,0x2E,0xCC,0x92,0xD3,0x8D,0x6F,0x31,0xB2,0xEC,0x0E,0x50,
            0xAF,0xF1,0x13,0x4D,0xCE,0x90,0x72,0x2C,0x6D,0x33,0xD1,0x8F,0x0C,0x52,0xB0,0xEE,
            0x32,0x6C,0x8E,0xD0,0x53,0x0D,0xEF,0xB1,0xF0,0xAE,0x4C,0x12,0x91,0xCF,0x2D,0x73,
            0xCA,0x94,0x76,0x28,0xAB,0xF5,0x17,0x49,0x08,0x56,0xB4,0xEA,0x69,0x37,0xD5,0x8B,
            0x57,0x09,0xEB,0xB5,0x36,0x68,0x8A,0xD4,0x95,0xCB,0x29,0x77,0xF4,0xAA,0x48,0x16,
            0xE9,0xB7,0x55,0x0B,0x88,0xD6,0x34,0x6A,0x2B,0x75,0x97,0xC9,0x4A,0x14,0xF6,0xA8,
            0x74,0x2A,0xC8,0x96,0x15,0x4B,0xA9,0xF7,0xB6,0xE8,0x0A,0x54,0xD7,0x89,0x6B,0x35
        )

        // CRC-16 table — polynomial 0x1021 (reflected), 256 entries
        private val CRC16_TABLE = intArrayOf(
            0x0000,0x1189,0x2312,0x329B,0x4624,0x57AD,0x6536,0x74BF,0x8C48,0x9DC1,0xAF5A,0xBED3,0xCA6C,0xDBE5,0xE97E,0xF8F7,
            0x1081,0x0108,0x3393,0x221A,0x56A5,0x472C,0x75B7,0x643E,0x9CC9,0x8D40,0xBFDB,0xAE52,0xDAED,0xCB64,0xF9FF,0xE876,
            0x2102,0x308B,0x0210,0x1399,0x6726,0x76AF,0x4434,0x55BD,0xAD4A,0xBCC3,0x8E58,0x9FD1,0xEB6E,0xFAE7,0xC87C,0xD9F5,
            0x3183,0x200A,0x1291,0x0318,0x77A7,0x662E,0x54B5,0x453C,0xBDCB,0xAC42,0x9ED9,0x8F50,0xFBEF,0xEA66,0xD8FD,0xC974,
            0x4204,0x538D,0x6116,0x709F,0x0420,0x15A9,0x2732,0x36BB,0xCE4C,0xDFC5,0xED5E,0xFCD7,0x8868,0x99E1,0xAB7A,0xBAF3,
            0x5285,0x430C,0x7197,0x601E,0x14A1,0x0528,0x37B3,0x263A,0xDECD,0xCF44,0xFDDF,0xEC56,0x98E9,0x8960,0xBBFB,0xAA72,
            0x6306,0x728F,0x4014,0x519D,0x2522,0x34AB,0x0630,0x17B9,0xEF4E,0xFEC7,0xCC5C,0xDDD5,0xA96A,0xB8E3,0x8A78,0x9BF1,
            0x7387,0x620E,0x5095,0x411C,0x35A3,0x242A,0x16B1,0x0738,0xFFCF,0xEE46,0xDCDD,0xCD54,0xB9EB,0xA862,0x9AF9,0x8B70,
            0x8408,0x9581,0xA71A,0xB693,0xC22C,0xD3A5,0xE13E,0xF0B7,0x0840,0x19C9,0x2B52,0x3ADB,0x4E64,0x5FED,0x6D76,0x7CFF,
            0x9489,0x8500,0xB79B,0xA612,0xD2AD,0xC324,0xF1BF,0xE036,0x18C1,0x0948,0x3BD3,0x2A5A,0x5EE5,0x4F6C,0x7DF7,0x6C7E,
            0xA50A,0xB483,0x8618,0x9791,0xE32E,0xF2A7,0xC03C,0xD1B5,0x2942,0x38CB,0x0A50,0x1BD9,0x6F66,0x7EEF,0x4C74,0x5DFD,
            0xB58B,0xA402,0x9699,0x8710,0xF3AF,0xE226,0xD0BD,0xC134,0x39C3,0x284A,0x1AD1,0x0B58,0x7FE7,0x6E6E,0x5CF5,0x4D7C,
            0xC60C,0xD785,0xE51E,0xF497,0x8028,0x91A1,0xA33A,0xB2B3,0x4A44,0x5BCD,0x6956,0x78DF,0x0C60,0x1DE9,0x2F72,0x3EFB,
            0xD68D,0xC704,0xF59F,0xE416,0x90A9,0x8120,0xB3BB,0xA232,0x5AC5,0x4B4C,0x79D7,0x685E,0x1CE1,0x0D68,0x3FF3,0x2E7A,
            0xE70E,0xF687,0xC41C,0xD595,0xA12A,0xB0A3,0x8238,0x93B1,0x6B46,0x7ACF,0x4854,0x59DD,0x2D62,0x3CEB,0x0E70,0x1FF9,
            0xF78F,0xE606,0xD49D,0xC514,0xB1AB,0xA022,0x92B9,0x8330,0x7BC7,0x6A4E,0x58D5,0x495C,0x3DE3,0x2C6A,0x1EF1,0x0F78
        )

        fun crc8(data: ByteArray, offset: Int, length: Int): Int {
            var c = 0x77
            for (i in offset until offset + length) {
                c = CRC8_TABLE[(c xor data[i].toInt()) and 0xFF]
            }
            return c and 0xFF
        }

        fun crc16(data: ByteArray, offset: Int, length: Int): Int {
            var c = 0x3692
            for (i in offset until offset + length) {
                c = CRC16_TABLE[(c xor data[i].toInt()) and 0xFF] xor (c shr 8)
            }
            return c and 0xFFFF
        }

        /**
         * Validates a raw response frame against the request it should answer.
         *
         * Checks magic, encoded length (must exactly match the byte count received —
         * catches truncated/appended reads), header CRC-8, full-frame CRC-16, the
         * response bit in cmdType, matching sequence number, reversed sender/receiver
         * routing, and matching command set/ID. Pure function, no I/O — every check
         * runs against the two byte arrays given.
         *
         * @return the response payload on success, or null on any mismatch
         */
        fun validateResponse(request: ByteArray, response: ByteArray): ByteArray? {
            if (response.size < 13) return null
            if (response[0] != 0x55.toByte()) return null

            val totalLength = (response[1].toInt() and 0xFF) or ((response[2].toInt() and 0x03) shl 8)
            if (totalLength < 13 || totalLength > 1023) return null
            if (totalLength != response.size) return null

            if (crc8(response, 0, 3) != (response[3].toInt() and 0xFF)) return null

            val expectedCrc16 = crc16(response, 0, totalLength - 2)
            val actualCrc16 = (response[totalLength - 2].toInt() and 0xFF) or
                ((response[totalLength - 1].toInt() and 0xFF) shl 8)
            if (expectedCrc16 != actualCrc16) return null

            if (request.size < 11) return null

            val cmdType = response[8].toInt() and 0xFF
            if ((cmdType and 0x80) == 0) return null // response bit not set

            if (response[6] != request[6] || response[7] != request[7]) return null // sequence
            if (response[4] != request[5] || response[5] != request[4]) return null // reversed routing
            if (response[9] != request[9] || response[10] != request[10]) return null // cmd set/id

            val payloadLength = totalLength - 13
            if (payloadLength <= 0) return ByteArray(0)
            return response.copyOfRange(11, 11 + payloadLength)
        }
    }
}

/**
 * Sends DUMPL frames to the controller's local TCP proxy at 127.0.0.1:40009.
 *
 * The proxy expects one frame per TCP connection: open, write, read ACK, close.
 * This matches the original DJI app and OpenFCC behaviour exactly.
 */
class DumplTransport {

    // Ports that DJI controllers may listen on for DUMPL commands.
    // RC2 uses 40009. RC Pro 2 and RC Plus use 40007 or 8901-8904.
    private val SCAN_PORTS = listOf(PORT, PORT_LED, 8901, 8902, 8903, 8904)

    /**
     * Finds which port the DUMPL proxy is listening on by trying each one.
     * Caches the result so subsequent calls don't need to scan.
     */
    private var discoveredPort: Int = -1

    /** Returns the port that was detected, or -1 if none found yet. */
    fun getDetectedPort(): Int = discoveredPort

    private fun findWorkingPort(): Int {
        if (discoveredPort > 0 && isReachable(discoveredPort)) return discoveredPort
        for (p in SCAN_PORTS) {
            if (isReachable(p)) {
                discoveredPort = p
                return p
            }
        }
        return PORT // fallback to default
    }

    /**
     * Sends a list of frames over multiple rounds, discarding ACKs.
     * Automatically finds the correct DUMPL port for the controller type.
     *
     * @param onProgress Called with a 0..1 float as frames are sent
     * @return true if at least one frame was written successfully
     */
    fun sendFrames(
        frames: List<ByteArray>,
        rounds: Int = 1,
        interFrameDelayMs: Long = 150,
        interRoundDelayMs: Long = 0,
        readWindowMs: Int = 80,
        port: Int = PORT,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        // If port is the default (40009), scan for the actual working port.
        // If port is explicitly set (e.g. 40007 for LED), use it directly.
        val effectivePort = if (port == PORT) findWorkingPort() else port

        var anySuccess = false
        val totalSends = frames.size * rounds
        var sent = 0

        for (round in 0 until rounds) {
            for (frame in frames) {
                if (sendOneFrame(frame, readWindowMs, effectivePort)) {
                    anySuccess = true
                }
                sent++
                onProgress(sent.toFloat() / totalSends)
                if (interFrameDelayMs > 0) Thread.sleep(interFrameDelayMs)
            }
            if (interRoundDelayMs > 0 && round < rounds - 1) {
                Thread.sleep(interRoundDelayMs)
            }
        }
        return anySuccess
    }

    /**
     * Sends a single frame and returns the raw response payload.
     * Used for request/response commands like version inquiry.
     *
     * The response is a full DUMPL frame (0x55 header + payload + CRC).
     * This method extracts and returns just the payload bytes.
     *
     * @return response payload, or null if no response was received
     */
    fun sendAndReceive(frame: ByteArray, readWindowMs: Int = 500, port: Int = PORT): ByteArray? {
        var socket: Socket? = null
        try {
            val effectivePort = if (port == PORT) findWorkingPort() else port
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, effectivePort), 2000)
            socket.tcpNoDelay = true
            socket.soTimeout = readWindowMs

            socket.getOutputStream().apply { write(frame); flush() }

            val input = socket.getInputStream()
            val header = readBytes(input, 11) ?: return null

            // Verify magic byte before trusting the encoded length enough to read more
            if (header[0] != 0x55.toByte()) return null

            // Extract total length from bytes 1-2 (11-bit LE) to know how much more to read
            val totalLength = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0x03) shl 8)
            if (totalLength < 13 || totalLength > 1023) return null

            // Read the rest (payload + 2 CRC bytes)
            val remaining = readBytes(input, totalLength - 11) ?: return null
            val response = header + remaining

            return DumplBuilder.validateResponse(frame, response)

        } catch (_: IOException) {
            return null
        } finally {
            try { socket?.close() } catch (_: IOException) {}
        }
    }

    /**
     * Probes for the aircraft serial number.
     *
     * Tries multiple methods:
     * 1. Passive listen on TCP 40009 for telemetry matching 1581XXXXXXXXXXX (aircraft serial)
     * 2. Also tries the old W[AM]xxx model code pattern as fallback
     *
     * The aircraft serial (1581...) is what 4G activation needs in its payload.
     * The model code (WA341, WM630) is shorter and used for display only.
     */
    fun probeSerial(timeoutMs: Int = 1500): String {
        // Try the full aircraft serial pattern first (1581 + 12-18 alphanumeric chars)
        val result = listenForSerial(Regex("1581[0-9A-Za-z]{12,18}"), timeoutMs)
        if (result.isNotEmpty()) return result

        // Fallback: try the model code pattern (W[AM]xxx)
        return listenForSerial(Regex("W[AM][0-9]{3}"), timeoutMs)
    }

    /**
     * Opens a TCP socket to the DUMPL proxy and listens for data
     * matching the given regex pattern.
     */
    private fun listenForSerial(pattern: Regex, timeoutMs: Int): String {
        var socket: Socket? = null
        try {
            val port = findWorkingPort()
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, port), 2000)
            socket.soTimeout = 200

            val buffer = StringBuilder()
            val buf = ByteArray(4096)
            val input = socket.getInputStream()
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                try {
                    val n = input.read(buf)
                    if (n > 0) {
                        buffer.append(String(buf, 0, n))
                        pattern.find(buffer.toString())?.let { return it.value }
                    }
                } catch (_: IOException) { /* read timeout — keep trying */ }
            }
        } catch (_: IOException) { /* connection failed */ }
        finally { try { socket?.close() } catch (_: IOException) {} }
        return ""
    }

    /**
     * Connects to the DUMPL proxy by scanning all known ports.
     * Caches the working port for subsequent calls.
     * Returns true if any port is reachable.
     */
    fun connect(): Boolean {
        for (p in SCAN_PORTS) {
            if (isReachable(p)) {
                discoveredPort = p
                return true
            }
        }
        discoveredPort = -1
        return false
    }

    /** Checks if the DUMPL proxy is reachable (controller is powered on). */
    fun isReachable(port: Int = PORT): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, port), 2000)
            true
        } catch (_: IOException) { false }
        finally { try { socket?.close() } catch (_: IOException) {} }
    }

    // --- Internal helpers ---

    /**
     * Sends a single frame via TCP. Used for FCC, CE, LED, device info.
     * Waits for the ACK so the controller has time to process the frame.
     */
    private fun sendOneFrame(frame: ByteArray, readWindowMs: Int, port: Int = PORT): Boolean {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, port), 2000)
            socket.tcpNoDelay = true
            socket.soTimeout = maxOf(readWindowMs, 200)

            socket.getOutputStream().apply { write(frame); flush() }

            // Wait for the ACK so the controller has time to process
            try { socket.getInputStream().read(ByteArray(2048)) } catch (_: IOException) {}
            // Small settle delay to ensure the controller finished processing
            Thread.sleep(20)
            return true
        } catch (_: IOException) { return false }
        finally { try { socket?.close() } catch (_: IOException) {} }
    }

    /**
     * Sends a single frame via Unix domain socket (abstract namespace).
     * Used for 4G activation. Fire-and-forget: write, flush, close.
     * No ACK read — the 4G module doesn't respond on this socket.
     */
    private fun sendOneFrameUnix(frame: ByteArray): Boolean {
        var socket: LocalSocket? = null
        try {
            socket = LocalSocket(LocalSocket.SOCKET_DGRAM)
            socket.connect(LocalSocketAddress(UNIX_SOCKET_4G, LocalSocketAddress.Namespace.ABSTRACT))

            socket.getOutputStream().apply { write(frame); flush() }
            return true
        } catch (_: IOException) { return false }
        finally { try { socket?.close() } catch (_: IOException) {} }
    }

    /**
     * Sends a list of frames via Unix domain socket for 4G activation.
     * Fire-and-forget: no ACK read, just write and close per frame.
     * Attempts every frame regardless of earlier failures, but only
     * returns true if every single write succeeded.
     */
    fun sendFramesUnix(
        frames: List<ByteArray>,
        interFrameDelayMs: Long = 10,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (frames.isEmpty()) return false

        var allSuccess = true
        val total = frames.size
        var sent = 0

        for (frame in frames) {
            if (!sendOneFrameUnix(frame)) {
                allSuccess = false
            }
            sent++
            onProgress(sent.toFloat() / total)
            if (interFrameDelayMs > 0) Thread.sleep(interFrameDelayMs)
        }
        return allSuccess
    }

    private fun readBytes(input: java.io.InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = try { input.read(out, read, count - read) } catch (_: IOException) { return null }
            if (n <= 0) return if (read > 0) out.copyOf(read) else null
            read += n
        }
        return out
    }

    companion object {
        private const val HOST = "127.0.0.1"
        const val PORT = 40009       // Standard DUMPL proxy port (FCC, CE, device info)
        const val PORT_LED = 40007   // LED control port
        // 4G frames go via Unix domain socket, not TCP
        private const val UNIX_SOCKET_4G = "/duss/mb/0x205"
    }
}