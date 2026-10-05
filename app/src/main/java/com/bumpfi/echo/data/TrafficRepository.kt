package com.bumpfi.echo.data

import com.bumpfi.echo.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


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

    // Running totals; written by VPN threads, pushed to the StateFlows in publish()
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val connectionTotal = AtomicInteger(0)
    private val dnsQueryTotal = AtomicInteger(0)

    // Protocol distribution tracking
    private val protocolCounts = ConcurrentHashMap<Protocol, Long>()

    // TLS version distribution tracking
    private val tlsVersionCounts = ConcurrentHashMap<TlsVersion, Int>()

    // Encrypted vs unencrypted traffic
    private val encryptedBytes = AtomicLong(0)
    private val unencryptedBytes = AtomicLong(0)

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
            // If this is a REAL destination (not unattributed), absorb any previously
            // accumulated unattributed bytes into this entry. This handles the race where
            // early poll cycles fire before DNS/TLS resolution completes.
            var extraRx = 0L
            var extraTx = 0L
            if (connection.destinationAddress != UNATTRIBUTED) {
                val unknownIndex = appTraffic.connections.indexOfFirst {
                    it.destinationAddress == UNATTRIBUTED && it.destinationPort == 0
                }
                if (unknownIndex >= 0) {
                    val unknownEntry = appTraffic.connections.removeAt(unknownIndex)
                    extraRx = unknownEntry.bytesReceived
                    extraTx = unknownEntry.bytesSent
                    // connectionCount was already incremented for the unattributed entry;
                    // we're replacing it, not adding a new one, so decrement
                    connectionTotal.decrementAndGet()
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
                    isEncrypted = if (existing.isEncrypted == true || connection.isEncrypted == true) true
                                  else existing.isEncrypted ?: connection.isEncrypted,
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
        bytesSent.addAndGet(connection.bytesSent)
        bytesReceived.addAndGet(connection.bytesReceived)
        packetsSent.addAndGet(connection.packetsSent)
        packetsReceived.addAndGet(connection.packetsReceived)
        if (isNewConnection) {
            connectionTotal.incrementAndGet()
        }

        // Track protocol distribution
        protocolCounts.merge(connection.protocol, connection.totalBytes, Long::plus)

        // Track encryption (unmeasured encryption is counted in neither bucket)
        when (connection.isEncrypted) {
            true -> encryptedBytes.addAndGet(connection.totalBytes)
            false -> unencryptedBytes.addAndGet(connection.totalBytes)
            null -> {}
        }

        // Track TLS version if available (only for new connections to avoid double-counting)
        if (isNewConnection && connection.tlsVersion != null) {
            try {
                val version = TlsVersion.valueOf(connection.tlsVersion)
                tlsVersionCounts.merge(version, 1, Int::plus)
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
                dnsQueryTotal.incrementAndGet()
            }
        }
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
                    tlsVersionCounts.merge(tlsInfo.tlsVersion, 1, Int::plus)
                }
            }
        }
    }

    /**
     * Clear all collected data.
     */
    fun clearAll() {
        trafficByApp.clear()
        protocolCounts.clear()
        tlsVersionCounts.clear()
        listOf(encryptedBytes, unencryptedBytes, bytesSent, bytesReceived, packetsSent, packetsReceived).forEach { it.set(0) }
        connectionTotal.set(0)
        dnsQueryTotal.set(0)
        sessionStartTime = 0
        publish()
    }

    /**
     * Push the current state to observers. Called once per stats poll (every 2s)
     * instead of on every record call, so the UI isn't flooded with updates.
     * Observers get copies of the per-app lists, safe to read while VPN threads keep writing.
     */
    fun publish() {
        _appTrafficList.value = trafficByApp.values.map { it.snapshot() }.sortedByDescending { it.totalBytes }
        _totalBytesSent.value = bytesSent.get()
        _totalBytesReceived.value = bytesReceived.get()
        _totalPacketsSent.value = packetsSent.get()
        _totalPacketsReceived.value = packetsReceived.get()
        _connectionCount.value = connectionTotal.get()
        _dnsQueryCount.value = dnsQueryTotal.get()
    }

    private fun AppTrafficInfo.snapshot(): AppTrafficInfo = copy(
        connections = synchronized(connections) { connections.toMutableList() },
        dnsQueries = synchronized(dnsQueries) { dnsQueries.toMutableList() },
        tlsConnections = synchronized(tlsConnections) { tlsConnections.toMutableList() }
    )

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
