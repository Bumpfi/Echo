package com.bumpfi.echo.data.model

import android.graphics.drawable.Drawable
import com.bumpfi.echo.data.tracking.ConnectionClassification
import com.bumpfi.echo.data.tracking.TrackerDatabase

/**
 * Represents traffic data for a single app.
 */
data class AppTrafficInfo(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val uid: Int,
    val totalBytesSent: Long = 0,
    val totalBytesReceived: Long = 0,
    val totalPacketsSent: Long = 0,
    val totalPacketsReceived: Long = 0,
    val connections: MutableList<ConnectionInfo> = mutableListOf(),
    val dnsQueries: MutableList<DnsQuery> = mutableListOf(),
    val tlsConnections: MutableList<TlsConnectionInfo> = mutableListOf(),
    val firstSeenTimestamp: Long = System.currentTimeMillis(),
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    // ===== 6.1 Traffic Metrics =====
    /** totalData = bytesSent + bytesReceived */
    val totalBytes: Long get() = totalBytesSent + totalBytesReceived

    /** trafficRate = totalData / measurementDuration (bytes per second) */
    fun getTrafficRate(measurementDurationMs: Long): Double {
        return if (measurementDurationMs > 0) {
            (totalBytes * 1000.0) / measurementDurationMs
        } else 0.0
    }

    /** uploadDownloadRatio = sent / received */
    val uploadDownloadRatio: Double get() = if (totalBytesReceived > 0) {
        totalBytesSent.toDouble() / totalBytesReceived.toDouble()
    } else if (totalBytesSent > 0) Double.POSITIVE_INFINITY else 0.0

    // ===== 6.2 Connection & Privacy Metrics =====
    /** totalConnections = number of connections */
    val connectionCount: Int get() = connections.size

    /** uniqueDestinations = unique destination IPs */
    val uniqueDestinations: Set<String> get() = connections.map { it.destinationAddress }.toSet()

    /** connectionsPerMinute = totalConnections / duration (minutes) */
    fun getConnectionsPerMinute(measurementDurationMs: Long): Double {
        val durationMinutes = measurementDurationMs / 60000.0
        return if (durationMinutes > 0) connectionCount / durationMinutes else 0.0
    }

    /** dnsQueries = number of DNS queries */
    val dnsQueryCount: Int get() = dnsQueries.size

    /** uniqueDomains = unique domains queried */
    val uniqueDomains: Set<String> get() = dnsQueries.map { it.domain }.toSet()

    /** destinationsPerMinute = uniqueDestinations / duration (minutes) */
    fun getDestinationsPerMinute(measurementDurationMs: Long): Double {
        val durationMinutes = measurementDurationMs / 60000.0
        return if (durationMinutes > 0) uniqueDestinations.size / durationMinutes else 0.0
    }

    // ===== 6.3 Encryption Metrics =====
    /** encryptedConnections = HTTPS/TLS connections */
    val encryptedConnections: Int get() = connections.count { it.isEncrypted }

    /** unencryptedConnections = HTTP connections */
    val unencryptedConnections: Int get() = connections.count { !it.isEncrypted }

    /** encryptionRate = encrypted / total (0-1 ratio) */
    val encryptionRate: Double get() = if (connectionCount > 0) {
        encryptedConnections.toDouble() / connectionCount.toDouble()
    } else 0.0

    // ===== 6.4 Protocol Analysis =====
    /** tcpConnections = TCP connections count */
    val tcpConnections: Int get() = connections.count { it.protocol == Protocol.TCP }

    /** udpConnections = UDP connections count */
    val udpConnections: Int get() = connections.count { it.protocol == Protocol.UDP || it.protocol == Protocol.QUIC }

    /** tcpShare = TCP / total (0-1 ratio) */
    val tcpShare: Double get() = if (connectionCount > 0) {
        tcpConnections.toDouble() / connectionCount.toDouble()
    } else 0.0

    /** udpShare = UDP / total (0-1 ratio) */
    val udpShare: Double get() = if (connectionCount > 0) {
        udpConnections.toDouble() / connectionCount.toDouble()
    } else 0.0

    // ===== 6.5 Third-Party Detection (Enhanced with TrackerDatabase) =====

    /**
     * Get connections classified as trackers (analytics, advertising, fingerprinting, telemetry).
     */
    fun getTrackerConnections(): List<ConnectionInfo> {
        return connections.filter { conn ->
            val hostname = conn.sniHostname ?: conn.hostname ?: getHostnamesForIp(conn.destinationAddress).firstOrNull()
            TrackerDatabase.classifyConnection(packageName, hostname).isTracker()
        }
    }

    /**
     * Get unique tracker domains contacted by this app.
     */
    fun getUniqueTrackerDomains(): Set<String> {
        val trackerDomains = mutableSetOf<String>()
        connections.forEach { conn ->
            val hostname = conn.sniHostname ?: conn.hostname ?: getHostnamesForIp(conn.destinationAddress).firstOrNull()
            if (hostname != null && TrackerDatabase.isTrackingDomain(hostname)) {
                trackerDomains.add(hostname)
            }
        }
        return trackerDomains
    }

    /**
     * Get advertising tracker connections specifically.
     */
    fun getAdvertisingConnections(): List<ConnectionInfo> {
        return connections.filter { conn ->
            val hostname = conn.sniHostname ?: conn.hostname ?: getHostnamesForIp(conn.destinationAddress).firstOrNull()
            TrackerDatabase.classifyConnection(packageName, hostname) == ConnectionClassification.TRACKER_ADVERTISING
        }
    }

    /**
     * Get analytics tracker connections specifically.
     */
    fun getAnalyticsConnections(): List<ConnectionInfo> {
        return connections.filter { conn ->
            val hostname = conn.sniHostname ?: conn.hostname ?: getHostnamesForIp(conn.destinationAddress).firstOrNull()
            TrackerDatabase.classifyConnection(packageName, hostname) == ConnectionClassification.TRACKER_ANALYTICS
        }
    }

    /**
     * thirdPartyDestinations = destinations that don't match app's expected domain.
     * Uses TrackerDatabase for accurate classification.
     */
    fun getThirdPartyDestinations(): Set<String> {
        return uniqueDestinations.filter { dest ->
            val hostnames = getHostnamesForIp(dest)
            val hostname = hostnames.firstOrNull()

            // Use TrackerDatabase for classification
            val classification = TrackerDatabase.classifyConnection(packageName, hostname)
            classification.isThirdParty()
        }.toSet()
    }

    /** thirdPartyRatio = thirdParty / uniqueDestinations (0-1 ratio) */
    fun getThirdPartyRatio(): Double {
        val thirdPartyCount = getThirdPartyDestinations().size
        return if (uniqueDestinations.isNotEmpty()) {
            thirdPartyCount.toDouble() / uniqueDestinations.size.toDouble()
        } else 0.0
    }

    /** externalDnsRatio = domains not matching app provider (0-1 ratio) */
    fun getExternalDnsRatio(): Double {
        val externalDomains = uniqueDomains.count { domain ->
            !TrackerDatabase.isFirstPartyDomain(packageName, domain)
        }
        return if (uniqueDomains.isNotEmpty()) {
            externalDomains.toDouble() / uniqueDomains.size.toDouble()
        } else 0.0
    }

    /** Get hostnames associated with an IP from DNS queries */
    private fun getHostnamesForIp(ip: String): List<String> {
        return dnsQueries
            .filter { it.responseAddresses.contains(ip) }
            .map { it.domain }
    }

}

/**
 * Represents a single network connection/flow.
 */
data class ConnectionInfo(
    val timestamp: Long,
    val destinationAddress: String,
    val destinationPort: Int,
    val sourcePort: Int = 0,
    val protocol: Protocol,
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val hostname: String? = null,
    val country: String? = null,
    val isEncrypted: Boolean = false,
    val tlsVersion: String? = null,
    val sniHostname: String? = null,
    val connectionDuration: Long = 0,
    val tcpFlags: Set<TcpFlag> = emptySet(),
    val isTracker: Boolean = false,
    val trackerCategory: String? = null
) {
    val totalBytes: Long get() = bytesSent + bytesReceived

    /** Display destination as "hostname (IP)" if hostname available, otherwise just IP */
    val displayDestination: String get() {
        val effectiveHostname = sniHostname ?: hostname
        return if (!effectiveHostname.isNullOrBlank() && effectiveHostname != destinationAddress) {
            "$effectiveHostname ($destinationAddress)"
        } else {
            destinationAddress
        }
    }
}

/**
 * TCP Flags for detailed packet analysis.
 */
enum class TcpFlag {
    SYN, ACK, FIN, RST, PSH, URG
}

/**
 * Represents a DNS query made by an app.
 */
data class DnsQuery(
    val timestamp: Long,
    val domain: String,
    val queryType: DnsQueryType,
    val responseAddresses: List<String> = emptyList(),
    val responseTime: Long = 0, // in milliseconds
    val responseTtl: Int = 0,
    val responseCode: DnsResponseCode = DnsResponseCode.NOERROR,
    val isBlocked: Boolean = false,
    val resolvedVia: DnsResolver = DnsResolver.SYSTEM,
    val querySize: Int = 0,       // Size of DNS query packet in bytes
    val responseSize: Int = 0     // Size of DNS response packet in bytes
)

/**
 * DNS response codes.
 */
enum class DnsResponseCode {
    NOERROR,    // Success
    FORMERR,    // Format error
    SERVFAIL,   // Server failure
    NXDOMAIN,   // Non-existent domain
    NOTIMP,     // Not implemented
    REFUSED,    // Query refused
    OTHER
}

/**
 * DNS resolver type.
 */
enum class DnsResolver {
    SYSTEM,
}

/**
 * Network protocol types.
 */
enum class Protocol {
    TCP,
    UDP,
    ICMP,
    QUIC,       // UDP-based QUIC protocol
    OTHER
}

/**
 * DNS query types.
 */
enum class DnsQueryType {
    A,      // IPv4 address
    AAAA,   // IPv6 address
    CNAME,  // Canonical name
    MX,     // Mail exchange
    TXT,    // Text record
    PTR,    // Pointer record
    NS,     // Nameserver
    SOA,    // Start of authority
    SRV,    // Service record
    HTTPS,  // HTTPS service binding
    OTHER
}

/**
 * TLS/SSL connection information for encrypted traffic analysis.
 */
data class TlsConnectionInfo(
    val timestamp: Long,
    val destinationAddress: String,
    val destinationPort: Int,
    val sniHostname: String?,
    val tlsVersion: TlsVersion,
    val cipherSuite: String? = null,
    val certificateIssuer: String? = null,
    val certificateSubject: String? = null,
    val alpnProtocol: String? = null,  // e.g., "h2", "http/1.1"
    val isPinned: Boolean = false,
    val handshakeTime: Long = 0        // in milliseconds
)

/**
 * TLS versions for scientific analysis.
 */
enum class TlsVersion {
    TLS_1_0,
    TLS_1_1,
    TLS_1_2,
    TLS_1_3,
    QUIC,       // QUIC uses TLS 1.3
    UNKNOWN
}

/**
 * Represents a captured packet.
 */
data class PacketInfo(
    val timestamp: Long,
    val sourceAddress: String,
    val sourcePort: Int,
    val destinationAddress: String,
    val destinationPort: Int,
    val protocol: Protocol,
    val length: Int,
    val uid: Int,
    val ipVersion: Int = 4,
    val ttl: Int = 0,
    val flags: Set<TcpFlag> = emptySet(),
    val tcpSeqNumber: Long = 0,
    val tcpAckNumber: Long = 0,
    val tcpWindowSize: Int = 0,
    val payload: ByteArray? = null,
    val isOutgoing: Boolean = true,
    val isEncrypted: Boolean = false,
    val sniHostname: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketInfo

        if (timestamp != other.timestamp) return false
        if (sourceAddress != other.sourceAddress) return false
        if (destinationAddress != other.destinationAddress) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + sourceAddress.hashCode()
        result = 31 * result + destinationAddress.hashCode()
        return result
    }
}

