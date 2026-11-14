package com.netshare.wifidirect.client

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Lightweight IPv4, TCP, and UDP packet parsing and synthesis utilities
 * for the Client VPN Tunnel.
 */
object PacketUtils {

    const val PROTOCOL_ICMP: Byte = 1
    const val PROTOCOL_TCP: Byte = 6
    const val PROTOCOL_UDP: Byte = 17

    const val TCP_FLAG_FIN = 0x01
    const val TCP_FLAG_SYN = 0x02
    const val TCP_FLAG_RST = 0x04
    const val TCP_FLAG_PSH = 0x08
    const val TCP_FLAG_ACK = 0x10

    data class ParsedIpPacket(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Byte,
        val srcIp: String,
        val dstIp: String,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val ipPayloadOffset: Int,
        val payloadLength: Int
    )

    data class ParsedTcpPacket(
        val srcPort: Int,
        val dstPort: Int,
        val seqNumber: Long,
        val ackNumber: Long,
        val dataOffset: Int,
        val flags: Int,
        val windowSize: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class ParsedUdpPacket(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun parseIp(data: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 20) return null
        val verAndHlen = data[0].toInt() and 0xFF
        val version = verAndHlen shr 4
        if (version != 4) return null
        val hlen = (verAndHlen and 0x0F) * 4
        if (hlen < 20 || length < hlen) return null

        val totalLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val validLen = minOf(totalLen, length)
        val protocol = data[9]

        val srcBytes = data.copyOfRange(12, 16)
        val dstBytes = data.copyOfRange(16, 20)

        val srcIp = InetAddress.getByAddress(srcBytes).hostAddress ?: ""
        val dstIp = InetAddress.getByAddress(dstBytes).hostAddress ?: ""

        return ParsedIpPacket(
            version = version,
            headerLength = hlen,
            totalLength = validLen,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            srcIpBytes = srcBytes,
            dstIpBytes = dstBytes,
            ipPayloadOffset = hlen,
            payloadLength = validLen - hlen
        )
    }

    fun parseTcp(data: ByteArray, ipPacket: ParsedIpPacket): ParsedTcpPacket? {
        val offset = ipPacket.ipPayloadOffset
        if (ipPacket.payloadLength < 20) return null

        val srcPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val dstPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

        val seq = ByteBuffer.wrap(data, offset + 4, 4).int.toLong() and 0xFFFFFFFFL
        val ack = ByteBuffer.wrap(data, offset + 8, 4).int.toLong() and 0xFFFFFFFFL

        val dataOffset = ((data[offset + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOffset < 20 || ipPacket.payloadLength < dataOffset) return null

        val flags = data[offset + 13].toInt() and 0xFF
        val window = ((data[offset + 14].toInt() and 0xFF) shl 8) or (data[offset + 15].toInt() and 0xFF)

        val tcpPayloadOffset = offset + dataOffset
        val tcpPayloadLength = ipPacket.totalLength - tcpPayloadOffset

        return ParsedTcpPacket(
            srcPort = srcPort,
            dstPort = dstPort,
            seqNumber = seq,
            ackNumber = ack,
            dataOffset = dataOffset,
            flags = flags,
            windowSize = window,
            payloadOffset = tcpPayloadOffset,
            payloadLength = maxOf(0, tcpPayloadLength)
        )
    }

    fun parseUdp(data: ByteArray, ipPacket: ParsedIpPacket): ParsedUdpPacket? {
        val offset = ipPacket.ipPayloadOffset
        if (ipPacket.payloadLength < 8) return null

        val srcPort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        val dstPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        val udpLen = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)

        val payloadOffset = offset + 8
        val payloadLength = maxOf(0, minOf(udpLen - 8, ipPacket.totalLength - payloadOffset))

        return ParsedUdpPacket(
            srcPort = srcPort,
            dstPort = dstPort,
            length = udpLen,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength
        )
    }

    fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seqNumber: Long,
        ackNumber: Long,
        flags: Int,
        windowSize: Int = 65535,
        payload: ByteArray? = null
    ): ByteArray {
        val payloadSize = payload?.size ?: 0
        val totalLength = 20 + 20 + payloadSize
        val packet = ByteArray(totalLength)

        // 1. IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0x00 // DSCP / ECN
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00 // Identification
        packet[5] = 0x00
        packet[6] = 0x40.toByte() // Flags: Don't Fragment
        packet[7] = 0x00
        packet[8] = 64.toByte() // TTL
        packet[9] = PROTOCOL_TCP
        // Checksum at [10..11] filled later
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // IP Checksum
        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // 2. TCP Header (20 bytes at offset 20)
        val tcpOffset = 20
        packet[tcpOffset] = (srcPort shr 8).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = (dstPort shr 8).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        ByteBuffer.wrap(packet, tcpOffset + 4, 4).putInt((seqNumber and 0xFFFFFFFFL).toInt())
        ByteBuffer.wrap(packet, tcpOffset + 8, 4).putInt((ackNumber and 0xFFFFFFFFL).toInt())

        packet[tcpOffset + 12] = 0x50.toByte() // Data offset: 5 (20 bytes), reserved 0
        packet[tcpOffset + 13] = flags.toByte()
        packet[tcpOffset + 14] = (windowSize shr 8).toByte()
        packet[tcpOffset + 15] = (windowSize and 0xFF).toByte()
        packet[tcpOffset + 16] = 0x00 // Checksum reset
        packet[tcpOffset + 17] = 0x00
        packet[tcpOffset + 18] = 0x00 // Urgent pointer
        packet[tcpOffset + 19] = 0x00

        // Payload
        if (payload != null && payloadSize > 0) {
            System.arraycopy(payload, 0, packet, 40, payloadSize)
        }

        // TCP Checksum (with pseudo header)
        val tcpLen = 20 + payloadSize
        val tcpChecksum = computeTcpChecksum(srcIp, dstIp, packet, tcpOffset, tcpLen)
        packet[tcpOffset + 16] = (tcpChecksum shr 8).toByte()
        packet[tcpOffset + 17] = (tcpChecksum and 0xFF).toByte()

        return packet
    }

    fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x40.toByte()
        packet[7] = 0x00
        packet[8] = 64.toByte()
        packet[9] = PROTOCOL_UDP
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val udpOffset = 20
        val udpLen = 8 + payload.size
        packet[udpOffset] = (srcPort shr 8).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = (dstPort shr 8).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = (udpLen shr 8).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00
        packet[udpOffset + 7] = 0x00

        System.arraycopy(payload, 0, packet, 28, payload.size)

        val udpChecksum = computeUdpChecksum(srcIp, dstIp, packet, udpOffset, udpLen)
        packet[udpOffset + 6] = (udpChecksum shr 8).toByte()
        packet[udpOffset + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    fun buildIcmpReply(originalPacket: ByteArray, length: Int): ByteArray? {
        val ip = parseIp(originalPacket, length) ?: return null
        if (ip.protocol != PROTOCOL_ICMP) return null
        val icmpOffset = ip.ipPayloadOffset
        if (ip.payloadLength < 8) return null
        val type = originalPacket[icmpOffset].toInt() and 0xFF
        if (type != 8) return null // Only reply to echo request (type 8)

        val reply = originalPacket.copyOf(ip.totalLength)
        // Swap IP
        System.arraycopy(ip.dstIpBytes, 0, reply, 12, 4)
        System.arraycopy(ip.srcIpBytes, 0, reply, 16, 4)
        reply[10] = 0
        reply[11] = 0
        val ipCheck = computeChecksum(reply, 0, 20)
        reply[10] = (ipCheck shr 8).toByte()
        reply[11] = (ipCheck and 0xFF).toByte()

        // Set ICMP type 0 (Echo Reply)
        reply[icmpOffset] = 0x00
        reply[icmpOffset + 2] = 0
        reply[icmpOffset + 3] = 0
        val icmpCheck = computeChecksum(reply, icmpOffset, ip.payloadLength)
        reply[icmpOffset + 2] = (icmpCheck shr 8).toByte()
        reply[icmpOffset + 3] = (icmpCheck and 0xFF).toByte()

        return reply
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeTcpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, tcpOffset: Int, tcpLen: Int): Int {
        var sum = 0
        // Pseudo header: Src IP (4), Dst IP (4), Zero (1), Protocol (1 = 6), TCP Length (2)
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += PROTOCOL_TCP.toInt() and 0xFF
        sum += tcpLen

        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i < end - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val res = sum.inv() and 0xFFFF
        return if (res == 0) 0xFFFF else res
    }

    private fun computeUdpChecksum(srcIp: ByteArray, dstIp: ByteArray, packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += PROTOCOL_UDP.toInt() and 0xFF
        sum += udpLen

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i < end - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val res = sum.inv() and 0xFFFF
        return if (res == 0) 0xFFFF else res
    }
}
