package com.bumpfi.echo.vpn

import android.util.Log
import com.bumpfi.echo.data.model.*
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for extracting detailed information from IP packets.
 * Supports IPv4, IPv6, TCP, UDP, DNS, and TLS/SNI extraction.
 */
object PacketParser {
    private const val TAG = "PacketParser"

    // IP Protocol Numbers
    private const val PROTO_ICMP = 1
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val PROTO_ICMPV6 = 58

    // Common ports
    private const val PORT_DNS = 53
    private const val PORT_HTTP = 80
    private const val PORT_HTTPS = 443
    private const val PORT_DOT = 853  // DNS over TLS
    private const val PORT_QUIC = 443  // QUIC typically uses 443

    // TLS Record Types
    private const val TLS_HANDSHAKE = 22
    private const val TLS_CLIENT_HELLO = 1

    /**
     * Parsed IP packet result.
     */
    data class ParsedPacket(
        val ipVersion: Int,
        val protocol: Protocol,
        val protocolNumber: Int,
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destinationAddress: InetAddress,
        val destinationPort: Int,
        val totalLength: Int,
        val payloadLength: Int,
        val ttl: Int,
        val tcpFlags: Set<TcpFlag> = emptySet(),
        val tcpSeqNumber: Long = 0,
        val tcpAckNumber: Long = 0,
        val tcpWindowSize: Int = 0,
        val payload: ByteArray? = null,
        val isEncrypted: Boolean = false,
        val sniHostname: String? = null,
        val tlsVersion: TlsVersion? = null,
        val dnsInfo: DnsParseResult? = null,
        val isQuic: Boolean = false
    )

    /**
     * DNS parsing result.
     */
    data class DnsParseResult(
        val transactionId: Int,
        val isResponse: Boolean,
        val queryType: DnsQueryType,
        val domain: String,
        val responseCode: DnsResponseCode = DnsResponseCode.NOERROR,
        val responseAddresses: List<String> = emptyList(),
        val ttl: Int = 0
    )

    /**
     * Parse an IPv4 packet.
     */
    fun parseIPv4(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null

        try {
            val versionIhl = buffer[0].toInt() and 0xFF
            val version = (versionIhl shr 4) and 0x0F
            if (version != 4) return null

            val ihl = (versionIhl and 0x0F) * 4
            if (length < ihl) return null

            val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            val ttl = buffer[8].toInt() and 0xFF
            val protocolNum = buffer[9].toInt() and 0xFF

            val srcBytes = ByteArray(4)
            val dstBytes = ByteArray(4)
            System.arraycopy(buffer, 12, srcBytes, 0, 4)
            System.arraycopy(buffer, 16, dstBytes, 0, 4)

            val srcAddr = InetAddress.getByAddress(srcBytes)
            val dstAddr = InetAddress.getByAddress(dstBytes)

            // Parse transport layer
            return when (protocolNum) {
                PROTO_TCP -> parseTcp(buffer, ihl, length, srcAddr, dstAddr, ttl, totalLength)
                PROTO_UDP -> parseUdp(buffer, ihl, length, srcAddr, dstAddr, ttl, totalLength)
                PROTO_ICMP -> ParsedPacket(
                    ipVersion = 4,
                    protocol = Protocol.ICMP,
                    protocolNumber = protocolNum,
                    sourceAddress = srcAddr,
                    sourcePort = 0,
                    destinationAddress = dstAddr,
                    destinationPort = 0,
                    totalLength = totalLength,
                    payloadLength = length - ihl,
                    ttl = ttl
                )
                else -> ParsedPacket(
                    ipVersion = 4,
                    protocol = Protocol.OTHER,
                    protocolNumber = protocolNum,
                    sourceAddress = srcAddr,
                    sourcePort = 0,
                    destinationAddress = dstAddr,
                    destinationPort = 0,
                    totalLength = totalLength,
                    payloadLength = length - ihl,
                    ttl = ttl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "IPv4 parse error: ${e.message}")
            return null
        }
    }

    /**
     * Parse an IPv6 packet.
     */
    fun parseIPv6(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 40) return null

        try {
            val version = (buffer[0].toInt() shr 4) and 0x0F
            if (version != 6) return null

            val payloadLength = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            var nextHeader = buffer[6].toInt() and 0xFF
            val hopLimit = buffer[7].toInt() and 0xFF

            val srcBytes = ByteArray(16)
            val dstBytes = ByteArray(16)
            System.arraycopy(buffer, 8, srcBytes, 0, 16)
            System.arraycopy(buffer, 24, dstBytes, 0, 16)

            val srcAddr = InetAddress.getByAddress(srcBytes)
            val dstAddr = InetAddress.getByAddress(dstBytes)

            // Skip extension headers
            var offset = 40
            var hops = 0
            while (nextHeader in setOf(0, 43, 44, 60, 51, 50) && hops < 8) {
                if (length < offset + 2) break
                val hdrLen = (buffer[offset + 1].toInt() and 0xFF) + 1
                val skip = hdrLen * 8
                if (length < offset + skip) break
                nextHeader = buffer[offset].toInt() and 0xFF
                offset += skip
                hops++
            }

            val totalLength = 40 + payloadLength

            return when (nextHeader) {
                PROTO_TCP -> parseTcp(buffer, offset, length, srcAddr, dstAddr, hopLimit, totalLength, ipVersion = 6)
                PROTO_UDP -> parseUdp(buffer, offset, length, srcAddr, dstAddr, hopLimit, totalLength, ipVersion = 6)
                PROTO_ICMPV6 -> ParsedPacket(
                    ipVersion = 6,
                    protocol = Protocol.ICMP,
                    protocolNumber = nextHeader,
                    sourceAddress = srcAddr,
                    sourcePort = 0,
                    destinationAddress = dstAddr,
                    destinationPort = 0,
                    totalLength = totalLength,
                    payloadLength = payloadLength,
                    ttl = hopLimit
                )
                else -> ParsedPacket(
                    ipVersion = 6,
                    protocol = Protocol.OTHER,
                    protocolNumber = nextHeader,
                    sourceAddress = srcAddr,
                    sourcePort = 0,
                    destinationAddress = dstAddr,
                    destinationPort = 0,
                    totalLength = totalLength,
                    payloadLength = payloadLength,
                    ttl = hopLimit
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "IPv6 parse error: ${e.message}")
            return null
        }
    }

    private fun parseTcp(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        srcAddr: InetAddress,
        dstAddr: InetAddress,
        ttl: Int,
        totalLength: Int,
        ipVersion: Int = 4
    ): ParsedPacket? {
        if (length < offset + 20) return null

        val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)

        val seqNum = ((buffer[offset + 4].toLong() and 0xFF) shl 24) or
                     ((buffer[offset + 5].toLong() and 0xFF) shl 16) or
                     ((buffer[offset + 6].toLong() and 0xFF) shl 8) or
                     (buffer[offset + 7].toLong() and 0xFF)

        val ackNum = ((buffer[offset + 8].toLong() and 0xFF) shl 24) or
                     ((buffer[offset + 9].toLong() and 0xFF) shl 16) or
                     ((buffer[offset + 10].toLong() and 0xFF) shl 8) or
                     (buffer[offset + 11].toLong() and 0xFF)

        val dataOffset = ((buffer[offset + 12].toInt() and 0xFF) shr 4) * 4
        val flags = buffer[offset + 13].toInt() and 0xFF
        val windowSize = ((buffer[offset + 14].toInt() and 0xFF) shl 8) or (buffer[offset + 15].toInt() and 0xFF)

        val tcpFlags = mutableSetOf<TcpFlag>()
        if ((flags and 0x01) != 0) tcpFlags.add(TcpFlag.FIN)
        if ((flags and 0x02) != 0) tcpFlags.add(TcpFlag.SYN)
        if ((flags and 0x04) != 0) tcpFlags.add(TcpFlag.RST)
        if ((flags and 0x08) != 0) tcpFlags.add(TcpFlag.PSH)
        if ((flags and 0x10) != 0) tcpFlags.add(TcpFlag.ACK)
        if ((flags and 0x20) != 0) tcpFlags.add(TcpFlag.URG)

        val payloadOffset = offset + dataOffset
        val payloadLength = length - payloadOffset

        // Extract payload for TLS/HTTP analysis
        var sniHostname: String? = null
        var tlsVersion: TlsVersion? = null
        var isEncrypted = false

        if (payloadLength > 0 && payloadOffset < length) {
            val payload = buffer.copyOfRange(payloadOffset, length)

            // Check for TLS Client Hello
            if (dstPort == PORT_HTTPS || dstPort == 8443) {
                val tlsInfo = parseTlsClientHello(payload)
                if (tlsInfo != null) {
                    sniHostname = tlsInfo.first
                    tlsVersion = tlsInfo.second
                    isEncrypted = true
                }
            }

            // Port 443 is typically encrypted
            if (dstPort == PORT_HTTPS || dstPort == 8443 || dstPort == PORT_DOT) {
                isEncrypted = true
            }
        }

        return ParsedPacket(
            ipVersion = ipVersion,
            protocol = Protocol.TCP,
            protocolNumber = PROTO_TCP,
            sourceAddress = srcAddr,
            sourcePort = srcPort,
            destinationAddress = dstAddr,
            destinationPort = dstPort,
            totalLength = totalLength,
            payloadLength = payloadLength.coerceAtLeast(0),
            ttl = ttl,
            tcpFlags = tcpFlags,
            tcpSeqNumber = seqNum,
            tcpAckNumber = ackNum,
            tcpWindowSize = windowSize,
            isEncrypted = isEncrypted,
            sniHostname = sniHostname,
            tlsVersion = tlsVersion
        )
    }

    private fun parseUdp(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        srcAddr: InetAddress,
        dstAddr: InetAddress,
        ttl: Int,
        totalLength: Int,
        ipVersion: Int = 4
    ): ParsedPacket? {
        if (length < offset + 8) return null

        val srcPort = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val udpLength = ((buffer[offset + 4].toInt() and 0xFF) shl 8) or (buffer[offset + 5].toInt() and 0xFF)

        val payloadOffset = offset + 8
        val payloadLength = (udpLength - 8).coerceAtLeast(0)

        var dnsInfo: DnsParseResult? = null
        var isQuic = false
        var isEncrypted = false

        if (payloadLength > 0 && payloadOffset < length) {
            val payload = buffer.copyOfRange(payloadOffset, minOf(payloadOffset + payloadLength, length))

            // Check for DNS
            if (dstPort == PORT_DNS || srcPort == PORT_DNS) {
                dnsInfo = parseDns(payload)
            }

            // Check for QUIC (UDP on port 443 with specific initial byte patterns)
            if ((dstPort == PORT_QUIC || srcPort == PORT_QUIC) && payload.isNotEmpty()) {
                // QUIC long header starts with 1 in the first bit
                val firstByte = payload[0].toInt() and 0xFF
                if ((firstByte and 0x80) != 0) {
                    isQuic = true
                    isEncrypted = true
                }
            }
        }

        return ParsedPacket(
            ipVersion = ipVersion,
            protocol = if (isQuic) Protocol.QUIC else Protocol.UDP,
            protocolNumber = PROTO_UDP,
            sourceAddress = srcAddr,
            sourcePort = srcPort,
            destinationAddress = dstAddr,
            destinationPort = dstPort,
            totalLength = totalLength,
            payloadLength = payloadLength,
            ttl = ttl,
            dnsInfo = dnsInfo,
            isQuic = isQuic,
            isEncrypted = isEncrypted || dstPort == PORT_DOT,
            tlsVersion = if (isQuic) TlsVersion.QUIC else null
        )
    }

    /**
     * Parse TLS Client Hello to extract SNI and TLS version.
     */
    private fun parseTlsClientHello(payload: ByteArray): Pair<String?, TlsVersion>? {
        if (payload.size < 6) return null

        try {
            // TLS Record: Content Type (1) + Version (2) + Length (2) + Fragment
            val contentType = payload[0].toInt() and 0xFF
            if (contentType != TLS_HANDSHAKE) return null

            val recordVersion = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
            val recordLength = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)

            if (payload.size < 5 + recordLength) return null

            // Handshake: Type (1) + Length (3) + Client Hello
            if (payload.size < 6) return null
            val handshakeType = payload[5].toInt() and 0xFF
            if (handshakeType != TLS_CLIENT_HELLO) return null

            // Client Hello: Version (2) + Random (32) + Session ID Length (1) + ...
            if (payload.size < 43) return null

            val clientVersion = ((payload[9].toInt() and 0xFF) shl 8) or (payload[10].toInt() and 0xFF)

            var offset = 43  // After version + random

            // Session ID
            if (offset >= payload.size) return Pair(null, mapTlsVersion(clientVersion))
            val sessionIdLen = payload[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen

            // Cipher Suites
            if (offset + 2 > payload.size) return Pair(null, mapTlsVersion(clientVersion))
            val cipherSuitesLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherSuitesLen

            // Compression Methods
            if (offset >= payload.size) return Pair(null, mapTlsVersion(clientVersion))
            val compressionLen = payload[offset].toInt() and 0xFF
            offset += 1 + compressionLen

            // Extensions
            if (offset + 2 > payload.size) return Pair(null, mapTlsVersion(clientVersion))
            val extensionsLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2

            val extensionsEnd = offset + extensionsLen
            var detectedVersion = mapTlsVersion(clientVersion)
            var sni: String? = null

            // Parse extensions
            while (offset + 4 <= extensionsEnd && offset + 4 <= payload.size) {
                val extType = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                val extLen = ((payload[offset + 2].toInt() and 0xFF) shl 8) or (payload[offset + 3].toInt() and 0xFF)
                offset += 4

                if (offset + extLen > payload.size) break

                when (extType) {
                    0x0000 -> { // SNI Extension
                        if (extLen >= 5 && offset + 5 <= payload.size) {
                            val sniListLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                            val sniType = payload[offset + 2].toInt() and 0xFF
                            val sniLen = ((payload[offset + 3].toInt() and 0xFF) shl 8) or (payload[offset + 4].toInt() and 0xFF)

                            if (sniType == 0 && offset + 5 + sniLen <= payload.size) {
                                sni = String(payload, offset + 5, sniLen, Charsets.US_ASCII)
                            }
                        }
                    }
                    0x002b -> { // Supported Versions Extension (TLS 1.3 indicator)
                        // If supported_versions exists and contains 0x0304, it's TLS 1.3
                        if (extLen > 0 && offset < payload.size) {
                            val versionsLen = payload[offset].toInt() and 0xFF
                            var vOffset = offset + 1
                            while (vOffset + 2 <= offset + extLen && vOffset + 2 <= payload.size) {
                                val ver = ((payload[vOffset].toInt() and 0xFF) shl 8) or (payload[vOffset + 1].toInt() and 0xFF)
                                if (ver == 0x0304) {
                                    detectedVersion = TlsVersion.TLS_1_3
                                    break
                                }
                                vOffset += 2
                            }
                        }
                    }
                }

                offset += extLen
            }

            return Pair(sni, detectedVersion)

        } catch (e: Exception) {
            Log.d(TAG, "TLS parse error: ${e.message}")
            return null
        }
    }

    private fun mapTlsVersion(version: Int): TlsVersion {
        return when (version) {
            0x0301 -> TlsVersion.TLS_1_0
            0x0302 -> TlsVersion.TLS_1_1
            0x0303 -> TlsVersion.TLS_1_2
            0x0304 -> TlsVersion.TLS_1_3
            else -> TlsVersion.UNKNOWN
        }
    }

    /**
     * Parse DNS packet.
     */
    fun parseDns(payload: ByteArray): DnsParseResult? {
        if (payload.size < 12) return null

        try {
            val transactionId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val flags = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
            val isResponse = (flags and 0x8000) != 0
            val rcode = flags and 0x000F

            val qdCount = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
            val anCount = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)

            // Parse question section
            var offset = 12
            var domain = ""
            var queryType = DnsQueryType.OTHER

            if (qdCount > 0) {
                val domainResult = parseDomainName(payload, offset)
                domain = domainResult.first
                offset = domainResult.second

                if (offset + 4 <= payload.size) {
                    val qtype = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                    queryType = mapDnsQueryType(qtype)
                    offset += 4  // QTYPE + QCLASS
                }
            }

            // Parse answers if response
            val responseAddresses = mutableListOf<String>()
            var ttl = 0

            if (isResponse && anCount > 0) {
                for (i in 0 until anCount) {
                    if (offset + 12 > payload.size) break

                    // Skip name (might be compressed)
                    val nameResult = parseDomainName(payload, offset)
                    offset = nameResult.second

                    if (offset + 10 > payload.size) break

                    val atype = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                    offset += 2  // TYPE
                    offset += 2  // CLASS

                    ttl = ((payload[offset].toInt() and 0xFF) shl 24) or
                          ((payload[offset + 1].toInt() and 0xFF) shl 16) or
                          ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                          (payload[offset + 3].toInt() and 0xFF)
                    offset += 4  // TTL

                    val rdLength = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                    offset += 2  // RDLENGTH

                    // Extract IP address
                    if (atype == 1 && rdLength == 4 && offset + 4 <= payload.size) {
                        // A record (IPv4)
                        val ip = "${payload[offset].toInt() and 0xFF}.${payload[offset + 1].toInt() and 0xFF}." +
                                "${payload[offset + 2].toInt() and 0xFF}.${payload[offset + 3].toInt() and 0xFF}"
                        responseAddresses.add(ip)
                    } else if (atype == 28 && rdLength == 16 && offset + 16 <= payload.size) {
                        // AAAA record (IPv6)
                        val ipBytes = payload.copyOfRange(offset, offset + 16)
                        val ip = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                        responseAddresses.add(ip)
                    }

                    offset += rdLength
                }
            }

            val responseCode = when (rcode) {
                0 -> DnsResponseCode.NOERROR
                1 -> DnsResponseCode.FORMERR
                2 -> DnsResponseCode.SERVFAIL
                3 -> DnsResponseCode.NXDOMAIN
                4 -> DnsResponseCode.NOTIMP
                5 -> DnsResponseCode.REFUSED
                else -> DnsResponseCode.OTHER
            }

            return DnsParseResult(
                transactionId = transactionId,
                isResponse = isResponse,
                queryType = queryType,
                domain = domain,
                responseCode = responseCode,
                responseAddresses = responseAddresses,
                ttl = ttl
            )

        } catch (e: Exception) {
            Log.d(TAG, "DNS parse error: ${e.message}")
            return null
        }
    }

    private fun parseDomainName(payload: ByteArray, startOffset: Int): Pair<String, Int> {
        val name = StringBuilder()
        var offset = startOffset
        var jumped = false
        var originalOffset = offset
        var jumps = 0

        while (offset < payload.size && jumps < 20) {
            val len = payload[offset].toInt() and 0xFF

            if (len == 0) {
                if (!jumped) originalOffset = offset + 1
                break
            }

            if ((len and 0xC0) == 0xC0) {
                // Compression pointer
                if (offset + 1 >= payload.size) break
                if (!jumped) originalOffset = offset + 2
                val pointer = ((len and 0x3F) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                offset = pointer
                jumped = true
                jumps++
                continue
            }

            if (name.isNotEmpty()) name.append(".")

            for (i in 1..len) {
                if (offset + i < payload.size) {
                    name.append(payload[offset + i].toInt().toChar())
                }
            }

            offset += len + 1
        }

        return Pair(name.toString(), if (jumped) originalOffset else offset + 1)
    }

    private fun mapDnsQueryType(qtype: Int): DnsQueryType {
        return when (qtype) {
            1 -> DnsQueryType.A
            2 -> DnsQueryType.NS
            5 -> DnsQueryType.CNAME
            6 -> DnsQueryType.SOA
            12 -> DnsQueryType.PTR
            15 -> DnsQueryType.MX
            16 -> DnsQueryType.TXT
            28 -> DnsQueryType.AAAA
            33 -> DnsQueryType.SRV
            65 -> DnsQueryType.HTTPS
            else -> DnsQueryType.OTHER
        }
    }
}

