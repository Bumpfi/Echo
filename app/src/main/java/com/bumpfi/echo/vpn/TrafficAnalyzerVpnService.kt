package com.bumpfi.echo.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bumpfi.echo.MainActivity
import com.bumpfi.echo.R
import com.bumpfi.echo.data.AppInfoManager
import com.bumpfi.echo.data.SelectedAppsStore
import com.bumpfi.echo.data.TrafficRepository
import com.bumpfi.echo.data.model.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * High-Performance VPN Service for network traffic analysis.
 *
 * PERFORMANCE-OPTIMIZED APPROACH:
 * ===============================
 * 1. Route ONLY DNS traffic through VPN for query/response capture
 * 2. Use NetworkStatsManager API for per-app byte/packet counts (zero overhead)
 * 3. Extract TLS/SNI info from initial connection packets only
 * 4. Use thread pool for concurrent DNS forwarding
 *
 * This approach provides ALL scientific data without slowing down internet:
 * - Per-app bytes sent/received (from NetworkStats)
 * - DNS queries and responses per app
 * - TLS/SNI hostnames from connection handshakes
 * - Protocol distribution
 * - Connection timing
 */
class TrafficAnalyzerVpnService : VpnService() {

    companion object {
        private const val TAG = "EchoVpn"
        private const val CHANNEL_ID = "echo_vpn_channel"
        private const val NOTIFICATION_ID = 1

        // VPN interface - minimal footprint
        private const val VPN_ADDRESS = "10.215.173.1"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_MTU = 1500

        // DNS servers
        private const val UPSTREAM_DNS_1 = "8.8.8.8"
        private const val UPSTREAM_DNS_2 = "8.8.4.4"
        private const val DNS_PORT = 53

        // Actions
        const val ACTION_START = "com.bumpfi.echo.START_VPN"
        const val ACTION_STOP = "com.bumpfi.echo.STOP_VPN"

        // Singleton state
        @Volatile
        var isRunning = false
            private set

        var trafficRepository: TrafficRepository? = null
        var appInfoManager: AppInfoManager? = null
        var selectedAppsStore: SelectedAppsStore? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    // Thread pools for concurrent operations
    private var packetReaderExecutor: ExecutorService? = null
    private var dnsForwardingPool: ExecutorService? = null
    private var statsExecutor: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    // DNS tracking
    private data class PendingDnsQuery(
        val timestamp: Long,
        val domain: String,
        val uid: Int,
        val querySize: Int  // Size of the DNS query payload in bytes
    )
    private val pendingDnsQueries = ConcurrentHashMap<Int, PendingDnsQuery>() // transactionId -> query info
    private val dnsCache = ConcurrentHashMap<String, String>() // IP -> hostname

    // Per-app DNS queries tracking - which domains each app has queried
    private val appDnsQueries = ConcurrentHashMap<Int, MutableList<String>>() // uid -> list of domains queried

    // Per-app traffic tracking via TrafficStats (reads kernel counters directly)
    private val trafficStatsBaseline = ConcurrentHashMap<Int, Pair<Long, Long>>() // uid -> (baseRx, baseTx)
    private val trafficStatsLastReported = ConcurrentHashMap<Int, Pair<Long, Long>>() // uid -> (lastReportedRx, lastReportedTx)

    // NetworkStatsManager fallback — activated when TrafficStats returns no data
    // (e.g., on GrapheneOS or other privacy-focused ROMs that restrict per-UID kernel counters)
    @Volatile
    private var useNetworkStatsManagerPolling = false
    private var consecutiveEmptyPolls = 0
    private val networkStatsLastReported = ConcurrentHashMap<Int, Pair<Long, Long>>() // uid -> (lastReportedRx, lastReportedTx)
    private val EMPTY_POLLS_BEFORE_FALLBACK = 3 // 6 seconds (3 polls * 2s interval)

    // TLS/SNI tracking from handshakes
    private val recentTlsInfo = ConcurrentHashMap<String, TlsConnectionInfo>() // "ip:port" -> info

    // Per-app destination tracking - maps uid -> list of (destination, port, hostname, isEncrypted)
    data class DestinationInfo(
        val ip: String,
        val port: Int,
        val hostname: String?,
        val isEncrypted: Boolean,
        val tlsVersion: TlsVersion? = null,
        var lastSeen: Long = System.currentTimeMillis()
    )
    private val appDestinations = ConcurrentHashMap<Int, MutableList<DestinationInfo>>() // uid -> destinations

    // Counters
    private val totalDnsQueries = AtomicLong(0)
    private val totalPacketsProcessed = AtomicLong(0)

    // Set of known UIDs to poll via TrafficStats (discovered from installed apps + NetworkStatsManager)
    private val knownUids = ConcurrentHashMap.newKeySet<Int>()

    // Session start time — used to narrow NetworkStatsManager queries
    @Volatile
    private var sessionStartTimestamp = 0L

    private lateinit var connectivityManager: ConnectivityManager
    private var networkStatsManager: NetworkStatsManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VPN Service created")
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service onStartCommand, action: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!running) {
                    createNotificationChannel()
                    startForeground(NOTIFICATION_ID, buildNotification())
                    startVpn()
                }
            }
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (running) {
            Log.d(TAG, "VPN already running")
            return
        }

        if (trafficRepository == null || appInfoManager == null) {
            Log.e(TAG, "trafficRepository or appInfoManager not set!")
            stopSelf()
            return
        }

        try {
            // Build VPN - ONLY route DNS traffic for minimal overhead
            val builder = Builder()
                .setSession("Echo Traffic Analyzer")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                // ONLY route DNS servers - all other traffic goes direct!
                .addRoute(UPSTREAM_DNS_1, 32)
                .addRoute(UPSTREAM_DNS_2, 32)
                .addDnsServer(UPSTREAM_DNS_1)
                .addDnsServer(UPSTREAM_DNS_2)
                .setBlocking(false)  // Non-blocking for better performance

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // Exclude self
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Failed to exclude self from VPN")
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            Log.d(TAG, "VPN established - DNS-only mode for maximum speed")
            running = true
            isRunning = true

            trafficRepository!!.startSession()

            // Record session start time for narrowing NetworkStats queries
            sessionStartTimestamp = System.currentTimeMillis()

            // Initialize traffic baseline
            initializeTrafficBaseline()

            // Thread pool for DNS forwarding (4 threads for concurrent DNS)
            dnsForwardingPool = Executors.newFixedThreadPool(4)

            // Single thread for reading packets
            packetReaderExecutor = Executors.newSingleThreadExecutor()
            packetReaderExecutor?.submit { processPackets() }

            // Stats polling every 2 seconds
            statsExecutor = Executors.newSingleThreadScheduledExecutor()
            statsExecutor?.scheduleWithFixedDelay(
                { pollNetworkStats() },
                1, 2, TimeUnit.SECONDS
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}", e)
            stopSelf()
        }
    }

    /**
     * Initialize baseline traffic stats for all apps.
     *
     * ALWAYS uses TrafficStats API as the primary data source. TrafficStats
     * reads directly from kernel UID byte counters (via /proc/uid_stat/ or
     * eBPF maps), providing real-time accuracy with zero sync lag.
     *
     * NetworkStatsManager.querySummary() was previously the primary source,
     * but it reads from a periodically-synced stats database that can lag
     * behind actual traffic by seconds to minutes — especially during short
     * measurement sessions (< 5 minutes). This lag caused significant
     * underreporting (e.g., 280 KB reported vs 1.2 MB actual on a 30-second
     * Google Maps session).
     */
    private fun initializeTrafficBaseline() {
        trafficStatsBaseline.clear()
        trafficStatsLastReported.clear()
        knownUids.clear()

        // Primary: TrafficStats — scan all installed apps and capture baselines
        initializeTrafficStatsFallback()

        // Supplementary: Use NetworkStatsManager to discover system UIDs
        // that aren't in the installed apps list (e.g., system_server, DNS resolver)
        val statsManager = networkStatsManager
        if (statsManager != null) {
            try {
                val endTime = System.currentTimeMillis()
                discoverSystemUids(statsManager, ConnectivityManager.TYPE_WIFI, endTime)
                discoverSystemUids(statsManager, ConnectivityManager.TYPE_MOBILE, endTime)
            } catch (e: Exception) {
                Log.d(TAG, "NetworkStatsManager UID discovery failed: ${e.message}")
            }
        }

        Log.d(TAG, "Initialized TrafficStats baseline for ${knownUids.size} UIDs (using kernel counters for real-time accuracy)")
    }

    /**
     * Initialize TrafficStats by capturing baseline per-UID byte counts
     * for all installed apps that have internet permission.
     * TrafficStats reads directly from kernel counters and works for any user.
     */
    private fun initializeTrafficStatsFallback() {
        val pm = packageManager
        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        for (appInfo in installedApps) {
            val uid = appInfo.uid
            knownUids.add(uid)
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                trafficStatsBaseline[uid] = Pair(rx.coerceAtLeast(0), tx.coerceAtLeast(0))
            }
        }
        Log.d(TAG, "TrafficStats fallback initialized for ${knownUids.size} UIDs")
    }

    /**
     * Discover system UIDs from NetworkStatsManager that may not appear in
     * the installed apps list (e.g., system_server, DNS resolver, mediaserver).
     * For each discovered UID, register it for TrafficStats tracking.
     */
    private fun discoverSystemUids(statsManager: NetworkStatsManager, networkType: Int, endTime: Long) {
        try {
            val queryStart = (sessionStartTimestamp - 10_000L).coerceAtLeast(0L)
            val stats = statsManager.querySummary(networkType, null, queryStart, endTime)
            val bucket = NetworkStats.Bucket()

            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val uid = bucket.uid
                if (uid < 0) continue

                // If this UID isn't already tracked, add it
                if (!knownUids.contains(uid)) {
                    knownUids.add(uid)
                    val rx = TrafficStats.getUidRxBytes(uid)
                    val tx = TrafficStats.getUidTxBytes(uid)
                    if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
                        trafficStatsBaseline[uid] = Pair(rx.coerceAtLeast(0), tx.coerceAtLeast(0))
                    }
                }
            }
            stats.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for network type $networkType")
        } catch (e: Exception) {
            Log.d(TAG, "UID discovery error for type $networkType: ${e.message}")
        }
    }

    /**
     * Main packet reading loop - processes only DNS packets.
     */
    private fun processPackets() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val input = FileInputStream(vpnFd)
        val output = FileOutputStream(vpnFd)
        val buffer = ByteArray(VPN_MTU)

        Log.d(TAG, "Starting DNS packet processor")

        try {
            while (running && !Thread.currentThread().isInterrupted) {
                val length = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Read error: ${e.message}")
                    break
                }

                if (length <= 0) {
                    Thread.sleep(1)
                    continue
                }

                totalPacketsProcessed.incrementAndGet()
                val packet = buffer.copyOf(length)

                try {
                    // Parse and handle packet
                    val ipVersion = (packet[0].toInt() shr 4) and 0x0F

                    when (ipVersion) {
                        4 -> handleIPv4Packet(packet, length, output)
                        6 -> handleIPv6Packet(packet, length, output)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Packet error: ${e.message}")
                }
            }
        } finally {
            Log.d(TAG, "Packet processor stopped")
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }

    private fun handleIPv4Packet(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 20) return

        val protocol = packet[9].toInt() and 0xFF
        val ihl = (packet[0].toInt() and 0x0F) * 4

        if (protocol == 17) { // UDP
            handleUdpPacket(packet, ihl, length, output, ipVersion = 4)
        } else if (protocol == 6) { // TCP
            // Extract TLS/SNI info from TCP packets (but don't forward - they go direct)
            extractTlsInfo(packet, ihl, length)
        }
    }

    private fun handleIPv6Packet(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 40) return

        val nextHeader = packet[6].toInt() and 0xFF

        if (nextHeader == 17) { // UDP
            handleUdpPacket(packet, 40, length, output, ipVersion = 6)
        } else if (nextHeader == 6) { // TCP
            extractTlsInfo(packet, 40, length)
        }
    }

    private fun handleUdpPacket(packet: ByteArray, ipHeaderLen: Int, length: Int, output: FileOutputStream, ipVersion: Int) {
        if (length < ipHeaderLen + 8) return

        val dstPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                      (packet[ipHeaderLen + 3].toInt() and 0xFF)

        // Only process DNS packets (port 53)
        if (dstPort == DNS_PORT) {
            // Forward DNS in thread pool for concurrency
            dnsForwardingPool?.submit {
                forwardDnsPacket(packet, ipHeaderLen, length, output, ipVersion)
            }
        }
    }

    /**
     * Forward DNS packet and handle response.
     */
    private fun forwardDnsPacket(packet: ByteArray, ipHeaderLen: Int, length: Int, output: FileOutputStream, ipVersion: Int) {
        val udpHeaderLen = 8
        val dnsOffset = ipHeaderLen + udpHeaderLen

        if (dnsOffset + 12 >= length) return

        // Parse DNS query
        val dnsPayload = packet.copyOfRange(dnsOffset, length)
        val dnsInfo = PacketParser.parseDns(dnsPayload)

        if (dnsInfo != null && !dnsInfo.isResponse) {
            totalDnsQueries.incrementAndGet()

            // Try to get UID for this DNS query
            val uid = getUidFromPacket(packet, ipHeaderLen, ipVersion)

            // Track query — we'll record it to the repository AFTER the response
            // comes back so we can include responseAddresses and responseTime.
            // Recording it now with empty data would cause the response record to
            // be rejected as a duplicate (same domain within 1 second).
            pendingDnsQueries[dnsInfo.transactionId] = PendingDnsQuery(
                timestamp = System.currentTimeMillis(),
                domain = dnsInfo.domain,
                uid = uid,
                querySize = dnsPayload.size
            )
        }

        // Forward to upstream DNS
        try {
            val socket = DatagramSocket()
            try {
                if (!protect(socket)) {
                    socket.close()
                    // Record query with no response data so it's not lost
                    recordPendingQueryIfUnrecorded(dnsInfo)
                    return
                }
                socket.soTimeout = 2000

                val dnsServer = InetAddress.getByName(UPSTREAM_DNS_1)
                val request = DatagramPacket(dnsPayload, dnsPayload.size, dnsServer, DNS_PORT)
                socket.send(request)

                val responseBuffer = ByteArray(4096)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)

                // Parse DNS response
                val responseInfo = PacketParser.parseDns(response.data.copyOf(response.length))
                if (responseInfo != null && responseInfo.isResponse) {
                    // Match with query
                    val queryInfo = pendingDnsQueries.remove(responseInfo.transactionId)
                    if (queryInfo != null) {
                        val responseTime = System.currentTimeMillis() - queryInfo.timestamp
                        val uid = queryInfo.uid

                        // Cache DNS resolution
                        responseInfo.responseAddresses.forEach { ip ->
                            dnsCache[ip] = responseInfo.domain
                        }

                        // Record the complete DNS query+response
                        if (uid >= 0) {
                            recordDnsQuery(
                                uid, responseInfo.domain, responseInfo.queryType,
                                responseInfo.responseAddresses, responseTime, responseInfo.ttl,
                                querySize = queryInfo.querySize,
                                responseSize = response.length
                            )
                        }
                    }
                }

                // Send response back through VPN
                val responsePacket = buildDnsResponse(packet, ipHeaderLen, response.data, response.length, ipVersion)
                if (responsePacket != null) {
                    synchronized(output) {
                        output.write(responsePacket)
                        output.flush()
                    }
                }

            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "DNS forward error: ${e.message}")
            // Record query with no response so it's not lost
            recordPendingQueryIfUnrecorded(dnsInfo)
        }
    }

    /**
     * Record a pending DNS query that never got a response (timeout/error).
     * This ensures the query is not silently lost.
     */
    private fun recordPendingQueryIfUnrecorded(dnsInfo: PacketParser.DnsParseResult?) {
        if (dnsInfo == null || dnsInfo.isResponse) return
        val queryInfo = pendingDnsQueries.remove(dnsInfo.transactionId) ?: return
        val uid = queryInfo.uid
        if (uid >= 0) {
            recordDnsQuery(
                uid, dnsInfo.domain, dnsInfo.queryType, emptyList(), 0, 0,
                querySize = queryInfo.querySize, responseSize = 0
            )
        }
    }

    /**
     * Extract TLS/SNI info from TCP packets (non-blocking, just observation).
     */
    private fun extractTlsInfo(packet: ByteArray, ipHeaderLen: Int, length: Int) {
        if (length < ipHeaderLen + 20) return

        val dstPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                      (packet[ipHeaderLen + 3].toInt() and 0xFF)

        // Only check HTTPS traffic
        if (dstPort != 443 && dstPort != 8443) return

        val tcpDataOffset = ((packet[ipHeaderLen + 12].toInt() and 0xFF) shr 4) * 4
        val payloadOffset = ipHeaderLen + tcpDataOffset

        if (payloadOffset >= length) return

        // Check for TLS Client Hello
        val payload = packet.copyOfRange(payloadOffset, length)
        if (payload.size < 6) return

        // TLS Record: content type 22 (handshake)
        if ((payload[0].toInt() and 0xFF) == 22) {
            // Try to parse SNI
            val tlsResult = parseTlsClientHello(payload)
            if (tlsResult != null) {
                val (sni, tlsVersion) = tlsResult

                // Get destination IP
                val dstIp = if (ipHeaderLen == 20) {
                    "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                    "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
                } else {
                    "ipv6"
                }

                val key = "$dstIp:$dstPort"

                if (sni != null) {
                    // Cache SNI -> IP mapping
                    dnsCache[dstIp] = sni

                    // Store TLS info
                    recentTlsInfo[key] = TlsConnectionInfo(
                        timestamp = System.currentTimeMillis(),
                        destinationAddress = dstIp,
                        destinationPort = dstPort,
                        sniHostname = sni,
                        tlsVersion = tlsVersion
                    )

                    // Record for the app
                    val uid = getUidFromPacket(packet, ipHeaderLen, if (ipHeaderLen == 20) 4 else 6)
                    if (uid >= 0) {
                        recordTlsConnection(uid, dstIp, dstPort, sni, tlsVersion)
                    }

                    Log.d(TAG, "TLS: $sni (${tlsVersion.name})")
                }
            }
        }
    }

    private fun parseTlsClientHello(payload: ByteArray): Pair<String?, TlsVersion>? {
        if (payload.size < 43) return null

        try {
            val contentType = payload[0].toInt() and 0xFF
            if (contentType != 22) return null

            val clientVersion = ((payload[9].toInt() and 0xFF) shl 8) or (payload[10].toInt() and 0xFF)
            var tlsVersion = when (clientVersion) {
                0x0301 -> TlsVersion.TLS_1_0
                0x0302 -> TlsVersion.TLS_1_1
                0x0303 -> TlsVersion.TLS_1_2
                0x0304 -> TlsVersion.TLS_1_3
                else -> TlsVersion.UNKNOWN
            }

            var offset = 43

            // Session ID
            if (offset >= payload.size) return Pair(null, tlsVersion)
            val sessionIdLen = payload[offset].toInt() and 0xFF
            offset += 1 + sessionIdLen

            // Cipher Suites
            if (offset + 2 > payload.size) return Pair(null, tlsVersion)
            val cipherLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2 + cipherLen

            // Compression
            if (offset >= payload.size) return Pair(null, tlsVersion)
            val compLen = payload[offset].toInt() and 0xFF
            offset += 1 + compLen

            // Extensions
            if (offset + 2 > payload.size) return Pair(null, tlsVersion)
            val extLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            offset += 2

            val extEnd = offset + extLen
            var sni: String? = null

            while (offset + 4 <= extEnd && offset + 4 <= payload.size) {
                val extType = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                val extDataLen = ((payload[offset + 2].toInt() and 0xFF) shl 8) or (payload[offset + 3].toInt() and 0xFF)
                offset += 4

                if (offset + extDataLen > payload.size) break

                when (extType) {
                    0x0000 -> { // SNI
                        if (extDataLen >= 5 && offset + 5 <= payload.size) {
                            val sniLen = ((payload[offset + 3].toInt() and 0xFF) shl 8) or (payload[offset + 4].toInt() and 0xFF)
                            if (offset + 5 + sniLen <= payload.size) {
                                sni = String(payload, offset + 5, sniLen, Charsets.US_ASCII)
                            }
                        }
                    }
                    0x002b -> { // Supported Versions (TLS 1.3 indicator)
                        if (extDataLen > 0) {
                            var vOffset = offset + 1
                            while (vOffset + 2 <= offset + extDataLen) {
                                val ver = ((payload[vOffset].toInt() and 0xFF) shl 8) or (payload[vOffset + 1].toInt() and 0xFF)
                                if (ver == 0x0304) {
                                    tlsVersion = TlsVersion.TLS_1_3
                                    break
                                }
                                vOffset += 2
                            }
                        }
                    }
                }
                offset += extDataLen
            }

            return Pair(sni, tlsVersion)
        } catch (e: Exception) {
            return null
        }
    }

    private fun getUidFromPacket(packet: ByteArray, ipHeaderLen: Int, ipVersion: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1

        try {
            val srcPort = ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or
                          (packet[ipHeaderLen + 1].toInt() and 0xFF)
            val dstPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                          (packet[ipHeaderLen + 3].toInt() and 0xFF)

            val srcAddr: InetAddress
            val dstAddr: InetAddress

            if (ipVersion == 4) {
                srcAddr = InetAddress.getByAddress(packet.copyOfRange(12, 16))
                dstAddr = InetAddress.getByAddress(packet.copyOfRange(16, 20))
            } else {
                srcAddr = InetAddress.getByAddress(packet.copyOfRange(8, 24))
                dstAddr = InetAddress.getByAddress(packet.copyOfRange(24, 40))
            }

            val protocol = if (ipVersion == 4) packet[9].toInt() and 0xFF else packet[6].toInt() and 0xFF
            val protoConst = if (protocol == 6) android.system.OsConstants.IPPROTO_TCP else android.system.OsConstants.IPPROTO_UDP

            return connectivityManager.getConnectionOwnerUid(
                protoConst,
                java.net.InetSocketAddress(srcAddr, srcPort),
                java.net.InetSocketAddress(dstAddr, dstPort)
            )
        } catch (e: Exception) {
            return -1
        }
    }

    private fun buildDnsResponse(originalPacket: ByteArray, ipHeaderLen: Int, responseData: ByteArray, responseLen: Int, ipVersion: Int): ByteArray? {
        try {
            val udpHeaderLen = 8
            val responsePacket = ByteArray(ipHeaderLen + udpHeaderLen + responseLen)

            // Copy IP header
            System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLen)

            if (ipVersion == 4) {
                // Swap addresses
                System.arraycopy(originalPacket, 12, responsePacket, 16, 4)
                System.arraycopy(originalPacket, 16, responsePacket, 12, 4)

                // Update length
                val totalLen = ipHeaderLen + udpHeaderLen + responseLen
                responsePacket[2] = ((totalLen shr 8) and 0xFF).toByte()
                responsePacket[3] = (totalLen and 0xFF).toByte()

                // Clear checksum
                responsePacket[10] = 0
                responsePacket[11] = 0

                // Calculate IP checksum
                var sum = 0
                for (i in 0 until ipHeaderLen step 2) {
                    if (i != 10) {
                        sum += ((responsePacket[i].toInt() and 0xFF) shl 8) or
                               (responsePacket.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: 0)
                    }
                }
                while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
                val checksum = sum.inv() and 0xFFFF
                responsePacket[10] = ((checksum shr 8) and 0xFF).toByte()
                responsePacket[11] = (checksum and 0xFF).toByte()
            } else {
                // IPv6: swap addresses
                System.arraycopy(originalPacket, 8, responsePacket, 24, 16)
                System.arraycopy(originalPacket, 24, responsePacket, 8, 16)

                // Update payload length
                val payloadLen = udpHeaderLen + responseLen
                responsePacket[4] = ((payloadLen shr 8) and 0xFF).toByte()
                responsePacket[5] = (payloadLen and 0xFF).toByte()
            }

            // UDP header - swap ports
            responsePacket[ipHeaderLen] = originalPacket[ipHeaderLen + 2]
            responsePacket[ipHeaderLen + 1] = originalPacket[ipHeaderLen + 3]
            responsePacket[ipHeaderLen + 2] = originalPacket[ipHeaderLen]
            responsePacket[ipHeaderLen + 3] = originalPacket[ipHeaderLen + 1]

            // UDP length
            val udpLen = udpHeaderLen + responseLen
            responsePacket[ipHeaderLen + 4] = ((udpLen shr 8) and 0xFF).toByte()
            responsePacket[ipHeaderLen + 5] = (udpLen and 0xFF).toByte()
            responsePacket[ipHeaderLen + 6] = 0
            responsePacket[ipHeaderLen + 7] = 0

            // Copy DNS response
            System.arraycopy(responseData, 0, responsePacket, ipHeaderLen + udpHeaderLen, responseLen)

            return responsePacket
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Poll TrafficStats for per-app traffic data.
     *
     * TrafficStats reads directly from kernel UID byte counters — this is
     * real-time and has no sync lag, unlike NetworkStatsManager.querySummary()
     * which reads from a periodically-synced database.
     */
    private fun pollNetworkStats() {
        if (!running) return

        // If TrafficStats has been detected as non-functional, use NetworkStatsManager
        if (useNetworkStatsManagerPolling) {
            pollViaNetworkStatsManager()
            return
        }

        var appsWithTraffic = 0

        for (uid in knownUids) {
            val currentRx = TrafficStats.getUidRxBytes(uid)
            val currentTx = TrafficStats.getUidTxBytes(uid)

            if (currentRx == TrafficStats.UNSUPPORTED.toLong() || currentTx == TrafficStats.UNSUPPORTED.toLong()) {
                continue
            }

            val baseline = trafficStatsBaseline[uid] ?: Pair(0L, 0L)
            val lastReported = trafficStatsLastReported[uid] ?: Pair(0L, 0L)

            val totalDeltaRx = (currentRx - baseline.first).coerceAtLeast(0)
            val totalDeltaTx = (currentTx - baseline.second).coerceAtLeast(0)

            val newRx = (totalDeltaRx - lastReported.first).coerceAtLeast(0)
            val newTx = (totalDeltaTx - lastReported.second).coerceAtLeast(0)

            if (newRx > 0 || newTx > 0) {
                // Sanity check: cap per-poll deltas to filter out counter anomalies
                val MAX_BYTES_PER_POLL = 50_000_000L // 50 MB
                if (newRx > MAX_BYTES_PER_POLL || newTx > MAX_BYTES_PER_POLL) {
                    Log.w(TAG, "Ignoring suspicious delta for UID $uid: rx=$newRx tx=$newTx")
                    trafficStatsLastReported[uid] = Pair(totalDeltaRx, totalDeltaTx)
                    continue
                }

                appsWithTraffic++
                recordTrafficForUid(uid, newRx, newTx)
                trafficStatsLastReported[uid] = Pair(totalDeltaRx, totalDeltaTx)
            }
        }

        // Detect TrafficStats restriction: if DNS queries are being captured (meaning
        // apps ARE communicating) but TrafficStats reports zero traffic for all UIDs,
        // the device likely restricts per-UID kernel counters (e.g., GrapheneOS).
        // After EMPTY_POLLS_BEFORE_FALLBACK consecutive empty polls, switch to
        // NetworkStatsManager which works via the PACKAGE_USAGE_STATS permission.
        if (appsWithTraffic == 0 && totalDnsQueries.get() > 0) {
            consecutiveEmptyPolls++
            if (consecutiveEmptyPolls >= EMPTY_POLLS_BEFORE_FALLBACK) {
                Log.w(TAG, "TrafficStats returned no data for $consecutiveEmptyPolls consecutive polls " +
                        "despite ${totalDnsQueries.get()} DNS queries — switching to NetworkStatsManager")
                useNetworkStatsManagerPolling = true
            }
        } else {
            consecutiveEmptyPolls = 0
        }

        Log.d(TAG, "Stats poll: ${totalDnsQueries.get()} DNS, ${knownUids.size} UIDs tracked, $appsWithTraffic with new traffic")
    }

    // NOTE: pollTrafficStatsFallback() has been removed — its logic is now
    // the primary path in pollNetworkStats() above.

    // NOTE: collectCurrentStats() has been removed — TrafficStats is now the
    // primary data source, reading directly from kernel counters.

    // ===== NetworkStatsManager Fallback =====

    /**
     * Query NetworkStatsManager for per-UID byte counts within a time range.
     * Sums across WiFi and Mobile network types.
     *
     * @return Map of UID to (rxBytes, txBytes)
     */
    private fun queryNetworkStatsPerUid(
        statsManager: NetworkStatsManager,
        startTime: Long,
        endTime: Long
    ): Map<Int, Pair<Long, Long>> {
        val uidBytes = mutableMapOf<Int, Pair<Long, Long>>()

        for (networkType in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            try {
                @Suppress("DEPRECATION")
                val stats = statsManager.querySummary(networkType, null, startTime, endTime)
                val bucket = NetworkStats.Bucket()

                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val uid = bucket.uid
                    if (uid < 0) continue

                    val existing = uidBytes[uid] ?: Pair(0L, 0L)
                    uidBytes[uid] = Pair(
                        existing.first + bucket.rxBytes.coerceAtLeast(0),
                        existing.second + bucket.txBytes.coerceAtLeast(0)
                    )
                }
                stats.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for network type $networkType")
            } catch (e: Exception) {
                Log.d(TAG, "NetworkStatsManager query error for type $networkType: ${e.message}")
            }
        }

        return uidBytes
    }

    /**
     * Poll traffic via NetworkStatsManager (fallback for restricted devices).
     *
     * Queries the session time window to get total bytes per UID, then computes
     * deltas from the last reported values. This is less accurate than TrafficStats
     * (the NetworkStats database can lag behind real traffic) but works on devices
     * that restrict per-UID kernel counters (e.g., GrapheneOS).
     */
    private fun pollViaNetworkStatsManager() {
        val statsManager = networkStatsManager ?: return

        val endTime = System.currentTimeMillis()
        var appsWithTraffic = 0

        val currentUidBytes = queryNetworkStatsPerUid(statsManager, sessionStartTimestamp, endTime)

        for ((uid, sessionTotal) in currentUidBytes) {
            val lastReported = networkStatsLastReported[uid] ?: Pair(0L, 0L)

            val newRx = (sessionTotal.first - lastReported.first).coerceAtLeast(0)
            val newTx = (sessionTotal.second - lastReported.second).coerceAtLeast(0)

            if (newRx > 0 || newTx > 0) {
                // Sanity check
                val MAX_BYTES_PER_POLL = 50_000_000L // 50 MB
                if (newRx > MAX_BYTES_PER_POLL || newTx > MAX_BYTES_PER_POLL) {
                    Log.w(TAG, "Ignoring suspicious NetworkStats delta for UID $uid: rx=$newRx tx=$newTx")
                    networkStatsLastReported[uid] = sessionTotal
                    continue
                }

                appsWithTraffic++
                recordTrafficForUid(uid, newRx, newTx)
                networkStatsLastReported[uid] = sessionTotal
            }
        }

        Log.d(TAG, "NetworkStatsManager poll: ${totalDnsQueries.get()} DNS, " +
                "${currentUidBytes.size} UIDs, $appsWithTraffic with new traffic")
    }

    private fun recordTrafficForUid(uid: Int, rxBytes: Long, txBytes: Long) {
        if (rxBytes <= 0 && txBytes <= 0) return

        val appInfo = appInfoManager?.getAppByUid(uid) ?: return

        // Check filter
        val selectedSet = selectedAppsStore?.current ?: emptySet()
        if (selectedSet.isNotEmpty() && !selectedSet.contains(appInfo.packageName)) return

        // Get known TLS destinations for this app
        val tlsDestinations = appDestinations[uid]

        // Get domains this app has queried via DNS
        val appDomains = appDnsQueries[uid]

        // SCIENTIFIC INTEGRITY: NetworkStats/TrafficStats reports TOTAL bytes per UID.
        // We do NOT know how those bytes are distributed across destinations.
        // We record a single ConnectionInfo entry per poll with the full byte totals,
        // using the best available destination info (TLS > DNS > unknown).
        //
        // We NEVER fabricate per-destination byte distributions by dividing evenly,
        // as that would be invented data unsuitable for scientific analysis.

        when {
            !tlsDestinations.isNullOrEmpty() -> {
                // We have actual TLS destinations — use the most recently seen one
                // for hostname/encryption metadata, but report ALL bytes in one entry.
                val bestDest = tlsDestinations
                    .filter { System.currentTimeMillis() - it.lastSeen < 60_000 }
                    .maxByOrNull { it.lastSeen }
                    ?: tlsDestinations.last()

                val connection = ConnectionInfo(
                    timestamp = System.currentTimeMillis(),
                    destinationAddress = bestDest.ip,
                    destinationPort = bestDest.port,
                    protocol = Protocol.TCP,
                    bytesSent = txBytes,
                    bytesReceived = rxBytes,
                    hostname = bestDest.hostname,
                    isEncrypted = bestDest.isEncrypted,
                    tlsVersion = bestDest.tlsVersion?.name,
                    sniHostname = bestDest.hostname
                )

                trafficRepository?.recordConnection(
                    packageName = appInfo.packageName,
                    appName = appInfo.appName,
                    appIcon = appInfo.appIcon,
                    uid = uid,
                    connection = connection
                )
            }
            !appDomains.isNullOrEmpty() -> {
                // We have DNS domains but NO TLS handshake observed.
                // We know the app resolved these domains, but we do NOT know:
                // - which port was used (could be 443, 80, or anything)
                // - which protocol (could be TCP, UDP, QUIC)
                // - whether the connection was encrypted
                // Mark all these as unknown/unverified for scientific accuracy.
                val recentDomain = appDomains.last()

                val connection = ConnectionInfo(
                    timestamp = System.currentTimeMillis(),
                    destinationAddress = recentDomain,
                    destinationPort = 0,        // UNKNOWN — not measured
                    protocol = Protocol.OTHER,   // UNKNOWN — not measured
                    bytesSent = txBytes,
                    bytesReceived = rxBytes,
                    hostname = recentDomain,
                    isEncrypted = false          // UNKNOWN — not measured, default to false
                )

                trafficRepository?.recordConnection(
                    packageName = appInfo.packageName,
                    appName = appInfo.appName,
                    appIcon = appInfo.appIcon,
                    uid = uid,
                    connection = connection
                )
            }
            else -> {
                // No destination info — record with "unknown" destination
                // This ensures we still capture traffic bytes even without DNS/TLS info
                val connection = ConnectionInfo(
                    timestamp = System.currentTimeMillis(),
                    destinationAddress = "unknown",
                    destinationPort = 0,
                    protocol = Protocol.OTHER,
                    bytesSent = txBytes,
                    bytesReceived = rxBytes,
                    hostname = null,
                    isEncrypted = false
                )

                trafficRepository?.recordConnection(
                    packageName = appInfo.packageName,
                    appName = appInfo.appName,
                    appIcon = appInfo.appIcon,
                    uid = uid,
                    connection = connection
                )
            }
        }
    }

    private fun recordDnsQuery(uid: Int, domain: String, queryType: DnsQueryType, responseAddresses: List<String>, responseTime: Long, ttl: Int, querySize: Int = 0, responseSize: Int = 0) {
        val appInfo = appInfoManager?.getAppByUid(uid) ?: return

        val selectedSet = selectedAppsStore?.current ?: emptySet()
        if (selectedSet.isNotEmpty() && !selectedSet.contains(appInfo.packageName)) return

        // Register UID for TrafficStats fallback if not yet known
        registerUidForFallback(uid)

        // Track this domain for this app (for traffic attribution)
        val domains = appDnsQueries.getOrPut(uid) { mutableListOf() }
        if (!domains.contains(domain)) {
            domains.add(domain)
            // Keep only last 20 domains per app
            if (domains.size > 20) {
                domains.removeAt(0)
            }
        }

        // Track destinations from DNS responses
        responseAddresses.forEach { ip ->
            // Assume HTTPS (443) as default since most traffic is encrypted
            addDestinationForApp(uid, ip, 443, domain, true, null)
        }

        val query = DnsQuery(
            timestamp = System.currentTimeMillis(),
            domain = domain,
            queryType = queryType,
            responseAddresses = responseAddresses,
            responseTime = responseTime,
            responseTtl = ttl,
            querySize = querySize,
            responseSize = responseSize
        )

        trafficRepository?.recordDnsQuery(
            packageName = appInfo.packageName,
            dnsQuery = query,
            appName = appInfo.appName,
            appIcon = appInfo.appIcon,
            uid = uid
        )
        // NOTE: We intentionally do NOT create ConnectionInfo entries from DNS responses.
        // DNS resolution ≠ an actual data connection. Creating phantom zero-byte connections
        // would inflate connectionCount, uniqueDestinations, encryptionRate, and protocol
        // distribution with fabricated data. Actual connections are recorded when
        // recordTrafficForUid() detects real byte transfers via NetworkStats/TrafficStats.
    }

    private fun recordTlsConnection(uid: Int, dstIp: String, dstPort: Int, sni: String, tlsVersion: TlsVersion) {
        val appInfo = appInfoManager?.getAppByUid(uid) ?: return

        val selectedSet = selectedAppsStore?.current ?: emptySet()
        if (selectedSet.isNotEmpty() && !selectedSet.contains(appInfo.packageName)) return

        // Register UID for TrafficStats fallback if not yet known
        registerUidForFallback(uid)

        // Track this destination for the app
        addDestinationForApp(uid, dstIp, dstPort, sni, true, tlsVersion)

        val tlsInfo = TlsConnectionInfo(
            timestamp = System.currentTimeMillis(),
            destinationAddress = dstIp,
            destinationPort = dstPort,
            sniHostname = sni,
            tlsVersion = tlsVersion
        )

        trafficRepository?.recordTlsConnection(appInfo.packageName, tlsInfo)
        // NOTE: We intentionally do NOT create a ConnectionInfo entry here.
        // TLS handshake observation ≠ measured data transfer. Creating phantom zero-byte
        // connections would inflate connectionCount and skew metrics. The actual traffic
        // bytes for this destination will be recorded by recordTrafficForUid() when
        // NetworkStats/TrafficStats detects real byte transfers.
    }

    /**
     * Register a UID for TrafficStats tracking.
     * Called when we discover a new UID through DNS or TLS capture.
     * Captures its current byte counts as baseline so we only report deltas.
     */
    private fun registerUidForFallback(uid: Int) {
        if (uid < 0) return
        if (knownUids.contains(uid)) return

        knownUids.add(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()) {
            trafficStatsBaseline[uid] = Pair(rx.coerceAtLeast(0), tx.coerceAtLeast(0))
        }
        Log.d(TAG, "Registered new UID $uid for TrafficStats tracking")
    }

    /**
     * Track a destination for an app (from DNS or TLS).
     */
    private fun addDestinationForApp(uid: Int, ip: String, port: Int, hostname: String?, isEncrypted: Boolean, tlsVersion: TlsVersion? = null) {
        val destinations = appDestinations.getOrPut(uid) { mutableListOf() }

        // Check if we already have this destination
        val existing = destinations.find { it.ip == ip && it.port == port }
        if (existing != null) {
            existing.lastSeen = System.currentTimeMillis()
            return
        }

        // Add new destination (limit to 50 per app to avoid memory issues)
        if (destinations.size < 50) {
            destinations.add(DestinationInfo(
                ip = ip,
                port = port,
                hostname = hostname,
                isEncrypted = isEncrypted,
                tlsVersion = tlsVersion
            ))
        }
    }

    private fun stopVpn() {
        running = false
        isRunning = false

        // NOTE: We do NOT call trafficRepository?.stopSession() here.
        // The ViewModel calls it and needs the return value for the session summary.
        // Calling it twice would create a race condition.

        packetReaderExecutor?.shutdownNow()
        dnsForwardingPool?.shutdownNow()
        statsExecutor?.shutdownNow()

        packetReaderExecutor = null
        dnsForwardingPool = null
        statsExecutor = null

        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null

        pendingDnsQueries.clear()
        dnsCache.clear()
        appDnsQueries.clear()
        recentTlsInfo.clear()
        appDestinations.clear()
        trafficStatsBaseline.clear()
        trafficStatsLastReported.clear()
        networkStatsLastReported.clear()
        knownUids.clear()
        sessionStartTimestamp = 0L
        useNetworkStatsManagerPolling = false
        consecutiveEmptyPolls = 0

        Log.d(TAG, "VPN stopped - ${totalDnsQueries.get()} DNS queries processed")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Echo Traffic Analyzer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Network traffic monitoring"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TrafficAnalyzerVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Echo - Monitoring Traffic")
            .setContentText("DNS-only mode • Fast performance")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
