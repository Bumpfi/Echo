package com.bumpfi.echo.data

import com.bumpfi.echo.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap


class TrafficRepository {

    // Traffic data by package name
    private val trafficByApp = ConcurrentHashMap<String, AppTrafficInfo>()


    // Observable state
    private val _appTrafficList = MutableStateFlow<List<AppTrafficInfo>>(emptyList())
    val appTrafficList: StateFlow<List<AppTrafficInfo>> = _appTrafficList.asStateFlow()

    private val _totalBytesSent = MutableStateFlow(0L)
    val totalBytesSent: StateFlow<Long> = _totalBytesSent.asStateFlow()

    private val _totalBytesReceived = MutableStateFlow(0L)
    val totalBytesReceived: StateFlow<Long> = _totalBytesReceived.asStateFlow()

    private val _totalPacketsSent = MutableStateFlow(0L)
    val totalPacketsSent: StateFlow<Long> = _totalPacketsSent.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0L)
    val totalPacketsReceived: StateFlow<Long> = _totalPacketsReceived.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _dnsQueryCount = MutableStateFlow(0)
    val dnsQueryCount: StateFlow<Int> = _dnsQueryCount.asStateFlow()

    // Protocol distribution tracking
    private val protocolCounts = ConcurrentHashMap<Protocol, Long>()

    // TLS version distribution tracking
    private val tlsVersionCounts = ConcurrentHashMap<TlsVersion, Int>()

    // Encrypted vs unencrypted traffic
    private var encryptedBytes = 0L
    private var unencryptedBytes = 0L

    private var sessionStartTime: Long = 0


    fun startSession() {
        clearAll()
        sessionStartTime = System.currentTimeMillis()
    }

    /**
     * Record a connection for an app.
     *
     * SCIENTIFIC INTEGRITY: If a ConnectionInfo already exists for the same
     * destination (address + port), we MERGE the new bytes into it rather than
     * creating a duplicate entry. This is because the polling loop
     * (every 2 seconds) reports incremental byte deltas — each call does NOT
     * represent a new TCP/UDP connection. Without merging, connectionCount
     * would reflect "number of poll cycles with traffic" instead of
     * "number of unique connections", corrupting the scientific data.
     */
    fun recordConnection(
        packageName: String,
        appName: String,
        appIcon: android.graphics.drawable.Drawable?,
        uid: Int,
        connection: ConnectionInfo
    ) {
        val now = System.currentTimeMillis()

        android.util.Log.d("TrafficRepo", "recordConnection: $appName bytes=${connection.bytesSent + connection.bytesReceived}")

        val appTraffic = trafficByApp.getOrPut(packageName) {
            AppTrafficInfo(
                packageName = packageName,
                appName = appName,
                appIcon = appIcon,
                uid = uid,
                firstSeenTimestamp = now
            )
        }

        var isNewConnection = false

        // Thread-safe update — merge into existing entry if same destination
        synchronized(appTraffic.connections) {
            // If this is a REAL destination (not "unknown"), absorb any previously
            // accumulated "unknown" bytes into this entry. This handles the race where
            // early poll cycles fire before DNS/TLS resolution completes.
            var extraRx = 0L
            var extraTx = 0L
            if (connection.destinationAddress != "unknown") {
                val unknownIndex = appTraffic.connections.indexOfFirst {
                    it.destinationAddress == "unknown" && it.destinationPort == 0
                }
                if (unknownIndex >= 0) {
                    val unknownEntry = appTraffic.connections.removeAt(unknownIndex)
                    extraRx = unknownEntry.bytesReceived
                    extraTx = unknownEntry.bytesSent
                    // connectionCount was already incremented for the unknown entry;
                    // we're replacing it, not adding a new one, so decrement
                    _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
                }
            }

            val existingIndex = appTraffic.connections.indexOfFirst {
                it.destinationAddress == connection.destinationAddress &&
                it.destinationPort == connection.destinationPort
            }

            if (existingIndex >= 0) {
                // Merge: accumulate bytes into the existing entry
                val existing = appTraffic.connections[existingIndex]
                val merged = existing.copy(
                    bytesSent = existing.bytesSent + connection.bytesSent + extraTx,
                    bytesReceived = existing.bytesReceived + connection.bytesReceived + extraRx,
                    packetsSent = existing.packetsSent + connection.packetsSent,
                    packetsReceived = existing.packetsReceived + connection.packetsReceived,
                    // Update metadata if the new entry has better info
                    hostname = connection.hostname ?: existing.hostname,
                    sniHostname = connection.sniHostname ?: existing.sniHostname,
                    isEncrypted = existing.isEncrypted || connection.isEncrypted,
                    tlsVersion = connection.tlsVersion ?: existing.tlsVersion,
                    connectionDuration = now - existing.timestamp
                )
                appTraffic.connections[existingIndex] = merged
            } else {
                // New destination — create a new entry (include absorbed unknown bytes)
                val newConn = if (extraRx > 0 || extraTx > 0) {
                    connection.copy(
                        bytesSent = connection.bytesSent + extraTx,
                        bytesReceived = connection.bytesReceived + extraRx
                    )
                } else {
                    connection
                }
                appTraffic.connections.add(newConn)
                isNewConnection = true
            }
        }

        // Update totals
        _totalBytesSent.value += connection.bytesSent
        _totalBytesReceived.value += connection.bytesReceived
        _totalPacketsSent.value += connection.packetsSent
        _totalPacketsReceived.value += connection.packetsReceived
        if (isNewConnection) {
            _connectionCount.value++
        }

        // Track protocol distribution
        val currentCount = protocolCounts.getOrDefault(connection.protocol, 0L)
        protocolCounts[connection.protocol] = currentCount + connection.totalBytes

        // Track encryption
        if (connection.isEncrypted) {
            encryptedBytes += connection.totalBytes
        } else {
            unencryptedBytes += connection.totalBytes
        }

        // Track TLS version if available (only for new connections to avoid double-counting)
        if (isNewConnection && connection.tlsVersion != null) {
            try {
                val version = TlsVersion.valueOf(connection.tlsVersion)
                val count = tlsVersionCounts.getOrDefault(version, 0)
                tlsVersionCounts[version] = count + 1
            } catch (e: Exception) {
                // Ignore invalid TLS version strings
            }
        }

        // Update the app's counters.
        // IMPORTANT: We must preserve the existing mutable lists (connections, dnsQueries,
        // tlsConnections). Kotlin data class copy() uses default parameter values for
        // unspecified fields, which would create NEW empty lists, silently dropping all
        // accumulated connection/DNS/TLS data.
        val updatedApp = appTraffic.copy(
            totalBytesSent = appTraffic.totalBytesSent + connection.bytesSent,
            totalBytesReceived = appTraffic.totalBytesReceived + connection.bytesReceived,
            totalPacketsSent = appTraffic.totalPacketsSent + connection.packetsSent,
            totalPacketsReceived = appTraffic.totalPacketsReceived + connection.packetsReceived,
            connections = appTraffic.connections,
            dnsQueries = appTraffic.dnsQueries,
            tlsConnections = appTraffic.tlsConnections,
            lastSeenTimestamp = now
        )
        trafficByApp[packageName] = updatedApp

        // Notify observers
        updateAppTrafficList()
    }

    /**
     * Record a DNS query for an app.
     * Creates the app entry if it doesn't exist.
     */
    fun recordDnsQuery(
        packageName: String,
        dnsQuery: DnsQuery,
        appName: String? = null,
        appIcon: android.graphics.drawable.Drawable? = null,
        uid: Int = -1
    ) {
        val now = System.currentTimeMillis()

        // Get or create app entry
        val appTraffic = trafficByApp.getOrPut(packageName) {
            AppTrafficInfo(
                packageName = packageName,
                appName = appName ?: packageName,
                appIcon = appIcon,
                uid = uid,
                firstSeenTimestamp = now
            )
        }

        synchronized(appTraffic.dnsQueries) {
            // Avoid duplicate queries (same domain within 1 second)
            val isDuplicate = appTraffic.dnsQueries.any {
                it.domain == dnsQuery.domain &&
                Math.abs(it.timestamp - dnsQuery.timestamp) < 1000
            }
            if (!isDuplicate) {
                appTraffic.dnsQueries.add(dnsQuery)
                _dnsQueryCount.value++
            }
        }
        updateAppTrafficList()
    }

    /**
     * Record a TLS connection for an app.
     */
    fun recordTlsConnection(packageName: String, tlsInfo: TlsConnectionInfo) {
        val appTraffic = trafficByApp[packageName]

        if (appTraffic != null) {
            synchronized(appTraffic.tlsConnections) {
                // Avoid duplicate TLS info (same destination within 1 second)
                val isDuplicate = appTraffic.tlsConnections.any {
                    it.destinationAddress == tlsInfo.destinationAddress &&
                    it.destinationPort == tlsInfo.destinationPort &&
                    Math.abs(it.timestamp - tlsInfo.timestamp) < 1000
                }
                if (!isDuplicate) {
                    appTraffic.tlsConnections.add(tlsInfo)

                    // Track TLS version distribution
                    val count = tlsVersionCounts.getOrDefault(tlsInfo.tlsVersion, 0)
                    tlsVersionCounts[tlsInfo.tlsVersion] = count + 1
                }
            }
            updateAppTrafficList()
        }
    }

    /**
     * Get traffic info for a specific app.
     */
    fun getAppTraffic(packageName: String): AppTrafficInfo? {
        return trafficByApp[packageName]
    }

    /**
     * Get all connections for an app.
     */
    fun getConnectionsForApp(packageName: String): List<ConnectionInfo> {
        return trafficByApp[packageName]?.connections?.toList() ?: emptyList()
    }

    /**
     * Clear all collected data.
     */
    fun clearAll() {
        trafficByApp.clear()
        protocolCounts.clear()
        tlsVersionCounts.clear()
        encryptedBytes = 0L
        unencryptedBytes = 0L
        _totalBytesSent.value = 0
        _totalBytesReceived.value = 0
        _totalPacketsSent.value = 0
        _totalPacketsReceived.value = 0
        _connectionCount.value = 0
        _dnsQueryCount.value = 0
        _appTrafficList.value = emptyList()
        sessionStartTime = 0
    }

    private fun updateAppTrafficList() {
        _appTrafficList.value = trafficByApp.values.sortedByDescending { it.totalBytes }
    }

    /**
     * Get session duration in milliseconds.
     */
    fun getSessionDuration(): Long {
        return if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0
        }
    }

    /**
     * Get session start time as Unix timestamp in milliseconds.
     */
    fun getSessionStartTime(): Long {
        return sessionStartTime
    }

}
