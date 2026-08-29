package com.bumpfi.echo.data.export

import android.content.Context
import android.os.Build
import com.bumpfi.echo.data.model.AppTrafficInfo
import com.bumpfi.echo.data.model.TlsVersion
import com.bumpfi.echo.data.tracking.TrackerDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

/**
 * CSV Exporter for scientific data analysis.
 * Provides two export types:
 * 1. Aggregated metrics (one row per app session) - for statistical analysis
 * 2. Raw data (connections, DNS, destinations) - for detailed scientific review
 */
class CsvExporter(private val context: Context) {

    companion object {
        // Aggregated metrics CSV header — only columns that can actually be measured
        // by the DNS-only VPN + NetworkStats/TrafficStats approach.
        private const val CSV_HEADER = "AppName,PackageName,Category,LicenseType,OS,Scenario,Run," +
                "SessionStartTimestamp,SessionEndTimestamp,DurationSec," +
                "TotalDataBytes,BytesSent,BytesReceived,TrafficRateBytesPerSec,UploadDownloadRatio," +
                "TotalConnections,UniqueDestinations,ConnectionsPerMin,DestinationsPerMin," +
                "DnsQueries,UniqueDomains,DnsQueriesPerMin," +
                "EncryptedConnections,UnencryptedConnections,EncryptionRate," +
                "TcpConnections,UdpConnections,TcpShare,UdpShare," +
                "ThirdPartyDestinations,ThirdPartyRatio,ExternalDnsRatio," +
                "TrackerConnections,TrackerRatio,AnalyticsTrackers,AdvertisingTrackers," +
                "UniqueTrackerDomains," +
                "UniqueHostnames,UniquePorts,PortDiversity," +
                "Tls12Connections,Tls13Connections,Tls13Share," +
                "AvgDnsResponseTimeMs,MaxDnsResponseTimeMs," +
                "ConnectionBurstRate,TrafficEntropy"

        // Raw connections CSV header - includes tracker classification
        private const val RAW_CONNECTIONS_HEADER = "AppName,PackageName,Timestamp,TimestampISO," +
                "DestinationIP,DestinationPort,SourcePort,Protocol," +
                "BytesSent,BytesReceived,PacketsSent,PacketsReceived," +
                "Hostname,SniHostname,Country,IsEncrypted,TlsVersion," +
                "ConnectionDurationMs,IsTracker,TrackerCategory,ConnectionClassification"

        // Raw DNS queries CSV header
        private const val RAW_DNS_HEADER = "AppName,PackageName,Timestamp,TimestampISO," +
                "Domain,QueryType,ResponseAddresses,ResponseTimeMs,ResponseTTL," +
                "QuerySizeBytes,ResponseSizeBytes,ResponseCode,IsBlocked,ResolvedVia"

        // Raw destinations summary CSV header
        private const val RAW_DESTINATIONS_HEADER = "AppName,PackageName," +
                "DestinationIP,Hostname,Port,Protocol,ConnectionCount,TotalBytes," +
                "IsEncrypted,IsThirdParty,Country"

        // Default values for metadata fields
        private const val DEFAULT_CATEGORY = "Unknown"
        private const val DEFAULT_LICENSE_TYPE = "Unknown"
        private const val DEFAULT_SCENARIO = "Default"
        private const val DEFAULT_RUN = 1

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    /**
     * Data class representing a single app measurement session for CSV export.
     * Contains comprehensive scientific metrics for academic research.
     */
    data class AppSessionMetrics(
        // Identification
        val appName: String,
        val packageName: String,
        val category: String = DEFAULT_CATEGORY,
        val licenseType: String = DEFAULT_LICENSE_TYPE,
        val os: String = getOsName(),
        val scenario: String = DEFAULT_SCENARIO,
        val run: Int = DEFAULT_RUN,

        // Session Timestamps
        val sessionStartTimestamp: Long,
        val sessionEndTimestamp: Long,
        val durationSec: Long,

        // Traffic Metrics
        val totalDataBytes: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        val trafficRateBytesPerSec: Double,
        val uploadDownloadRatio: Double,

        // Connection Metrics
        val totalConnections: Int,
        val uniqueDestinations: Int,
        val connectionsPerMin: Double,
        val destinationsPerMin: Double,

        // DNS Metrics
        val dnsQueries: Int,
        val uniqueDomains: Int,
        val dnsQueriesPerMin: Double,

        // Encryption Metrics
        val encryptedConnections: Int,
        val unencryptedConnections: Int,
        val encryptionRate: Double,

        // Protocol Metrics
        val tcpConnections: Int,
        val udpConnections: Int,
        val tcpShare: Double,
        val udpShare: Double,

        // Third-Party / Tracking Metrics
        val thirdPartyDestinations: Int,
        val thirdPartyRatio: Double,
        val externalDnsRatio: Double,

        // Tracker Metrics (from TrackerDatabase)
        val trackerConnections: Int,
        val trackerRatio: Double,
        val analyticsTrackers: Int,
        val advertisingTrackers: Int,
        val uniqueTrackerDomains: Int,

        // Hostname & Port Metrics
        val uniqueHostnames: Int,
        val uniquePorts: Int,
        val portDiversity: Double, // Shannon entropy of port distribution

        // TLS Version Distribution
        val tls12Connections: Int,
        val tls13Connections: Int,
        val tls13Share: Double,


        // DNS Response Time Metrics
        val avgDnsResponseTimeMs: Double,
        val maxDnsResponseTimeMs: Long,

        // Advanced Scientific Metrics
        val connectionBurstRate: Double, // Connections per second in busiest minute
        val trafficEntropy: Double // Shannon entropy of traffic distribution
    ) {
        companion object {
            private fun getOsName(): String {
                return if (isGrapheneOS()) "GrapheneOS" else "Android"
            }

            private fun isGrapheneOS(): Boolean {
                return try {
                    val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
                    val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
                    fingerprint.contains("graphene") || manufacturer.contains("graphene")
                } catch (e: Exception) {
                    false
                }
            }
        }

        /**
         * Convert metrics to CSV row string.
         * Uses dot as decimal separator, comma as field separator.
         */
        fun toCsvRow(): String {
            return listOf(
                escapeCsvField(appName),
                escapeCsvField(packageName),
                escapeCsvField(category),
                escapeCsvField(licenseType),
                escapeCsvField(os),
                escapeCsvField(scenario),
                run.toString(),
                sessionStartTimestamp.toString(),
                sessionEndTimestamp.toString(),
                durationSec.toString(),
                totalDataBytes.toString(),
                bytesSent.toString(),
                bytesReceived.toString(),
                formatDouble(trafficRateBytesPerSec),
                formatDouble(uploadDownloadRatio),
                totalConnections.toString(),
                uniqueDestinations.toString(),
                formatDouble(connectionsPerMin),
                formatDouble(destinationsPerMin),
                dnsQueries.toString(),
                uniqueDomains.toString(),
                formatDouble(dnsQueriesPerMin),
                encryptedConnections.toString(),
                unencryptedConnections.toString(),
                formatDouble(encryptionRate),
                tcpConnections.toString(),
                udpConnections.toString(),
                formatDouble(tcpShare),
                formatDouble(udpShare),
                thirdPartyDestinations.toString(),
                formatDouble(thirdPartyRatio),
                formatDouble(externalDnsRatio),
                trackerConnections.toString(),
                formatDouble(trackerRatio),
                analyticsTrackers.toString(),
                advertisingTrackers.toString(),
                uniqueTrackerDomains.toString(),
                uniqueHostnames.toString(),
                uniquePorts.toString(),
                formatDouble(portDiversity),
                tls12Connections.toString(),
                tls13Connections.toString(),
                formatDouble(tls13Share),
                formatDouble(avgDnsResponseTimeMs),
                maxDnsResponseTimeMs.toString(),
                formatDouble(connectionBurstRate),
                formatDouble(trafficEntropy)
            ).joinToString(",")
        }

        /**
         * Format double with dot decimal separator (locale-independent).
         */
        private fun formatDouble(value: Double): String {
            return if (value.isNaN() || value.isInfinite()) {
                "0"
            } else {
                String.format(Locale.US, "%.4f", value)
            }
        }

        /**
         * Escape CSV field to handle commas and quotes.
         */
        private fun escapeCsvField(field: String): String {
            return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
                "\"${field.replace("\"", "\"\"")}\""
            } else {
                field
            }
        }
    }

    /**
     * Aggregate metrics from AppTrafficInfo for a measurement session.
     * Computes comprehensive scientific metrics for academic research.
     *
     * @param app The app traffic info to aggregate
     * @param sessionDurationMs Session duration in milliseconds
     * @param sessionStartTime Session start timestamp
     * @param category App category (e.g., "Navigation", "Social")
     * @param licenseType License type ("Proprietary" or "OpenSource")
     * @param scenario Measurement scenario name
     * @param run Run number for repeated measurements
     * @return AppSessionMetrics ready for CSV export
     */
    fun aggregateAppMetrics(
        app: AppTrafficInfo,
        sessionDurationMs: Long,
        sessionStartTime: Long = System.currentTimeMillis() - sessionDurationMs,
        category: String = DEFAULT_CATEGORY,
        licenseType: String = DEFAULT_LICENSE_TYPE,
        scenario: String = DEFAULT_SCENARIO,
        run: Int = DEFAULT_RUN
    ): AppSessionMetrics {
        val sessionEndTime = sessionStartTime + sessionDurationMs
        val durationSec = safeDivide(sessionDurationMs, 1000L).toLong().coerceAtLeast(1)
        val durationMin = durationSec / 60.0

        // Traffic Metrics
        val totalDataBytes = app.totalBytes
        val bytesSent = app.totalBytesSent
        val bytesReceived = app.totalBytesReceived
        val trafficRateBytesPerSec = safeDivide(totalDataBytes.toDouble(), durationSec.toDouble())
        val uploadDownloadRatio = if (bytesReceived > 0) {
            bytesSent.toDouble() / bytesReceived.toDouble()
        } else if (bytesSent > 0) Double.MAX_VALUE else 0.0


        // Connection Metrics
        val totalConnections = app.connectionCount
        val uniqueDestinations = app.uniqueDestinations.size
        val connectionsPerMin = safeDivide(totalConnections.toDouble(), durationMin)
        val destinationsPerMin = safeDivide(uniqueDestinations.toDouble(), durationMin)

        // DNS Metrics
        val dnsQueries = app.dnsQueryCount
        val uniqueDomains = app.uniqueDomains.size
        val dnsQueriesPerMin = safeDivide(dnsQueries.toDouble(), durationMin)

        // Encryption Metrics (rate as 0-1 ratio)
        val encryptedConnections = app.encryptedConnections
        val unencryptedConnections = app.unencryptedConnections
        val encryptionRate = safeDivide(encryptedConnections.toDouble(), totalConnections.toDouble())

        // Protocol Metrics (shares as 0-1 ratio)
        val tcpConnections = app.tcpConnections
        val udpConnections = app.udpConnections
        val tcpShare = safeDivide(tcpConnections.toDouble(), totalConnections.toDouble())
        val udpShare = safeDivide(udpConnections.toDouble(), totalConnections.toDouble())

        // Third-Party Metrics (ratio as 0-1)
        val thirdPartyDestinations = app.getThirdPartyDestinations().size
        val thirdPartyRatio = safeDivide(thirdPartyDestinations.toDouble(), uniqueDestinations.toDouble())
        val externalDnsRatio = app.getExternalDnsRatio() // Already 0-1 ratio

        // Tracker Metrics (using TrackerDatabase)
        val trackerConnections = app.getTrackerConnections().size
        val trackerRatio = safeDivide(trackerConnections.toDouble(), totalConnections.toDouble())
        val analyticsTrackers = app.getAnalyticsConnections().size
        val advertisingTrackers = app.getAdvertisingConnections().size
        val uniqueTrackerDomains = app.getUniqueTrackerDomains().size

        // Unique hostnames from connections (SNI and DNS)
        val uniqueHostnames = collectUniqueHostnames(app).size

        // Port diversity metrics
        val ports = app.connections.map { it.destinationPort }
        val uniquePorts = ports.toSet().size
        val portDiversity = calculateShannonEntropy(ports.groupBy { it }.mapValues { it.value.size })

        // TLS Version Distribution
        val tlsVersionCounts = countTlsVersions(app)
        val tls12Connections = tlsVersionCounts[TlsVersion.TLS_1_2] ?: 0
        val tls13Connections = (tlsVersionCounts[TlsVersion.TLS_1_3] ?: 0) + (tlsVersionCounts[TlsVersion.QUIC] ?: 0)
        val tls13Share = safeDivide(tls13Connections.toDouble(), encryptedConnections.toDouble())


        // DNS Response Time Metrics
        val dnsResponseTimes = app.dnsQueries.map { it.responseTime }.filter { it > 0 }
        val avgDnsResponseTimeMs = if (dnsResponseTimes.isNotEmpty()) {
            dnsResponseTimes.average()
        } else 0.0
        val maxDnsResponseTimeMs = dnsResponseTimes.maxOrNull() ?: 0L

        // Connection Burst Rate (max connections per minute window)
        val connectionBurstRate = calculateBurstRate(app.connections.map { it.timestamp }, 60000)

        // Traffic Entropy (distribution of bytes across destinations)
        val bytesPerDestination = app.connections.groupBy { it.destinationAddress }
            .mapValues { entry -> entry.value.sumOf { it.totalBytes }.toInt() }
        val trafficEntropy = calculateShannonEntropy(bytesPerDestination)

        return AppSessionMetrics(
            appName = app.appName,
            packageName = app.packageName,
            category = category,
            licenseType = licenseType,
            scenario = scenario,
            run = run,
            sessionStartTimestamp = sessionStartTime,
            sessionEndTimestamp = sessionEndTime,
            durationSec = durationSec,
            totalDataBytes = totalDataBytes,
            bytesSent = bytesSent,
            bytesReceived = bytesReceived,
            trafficRateBytesPerSec = trafficRateBytesPerSec,
            uploadDownloadRatio = uploadDownloadRatio,
            totalConnections = totalConnections,
            uniqueDestinations = uniqueDestinations,
            connectionsPerMin = connectionsPerMin,
            destinationsPerMin = destinationsPerMin,
            dnsQueries = dnsQueries,
            uniqueDomains = uniqueDomains,
            dnsQueriesPerMin = dnsQueriesPerMin,
            encryptedConnections = encryptedConnections,
            unencryptedConnections = unencryptedConnections,
            encryptionRate = encryptionRate,
            tcpConnections = tcpConnections,
            udpConnections = udpConnections,
            tcpShare = tcpShare,
            udpShare = udpShare,
            thirdPartyDestinations = thirdPartyDestinations,
            thirdPartyRatio = thirdPartyRatio,
            externalDnsRatio = externalDnsRatio,
            trackerConnections = trackerConnections,
            trackerRatio = trackerRatio,
            analyticsTrackers = analyticsTrackers,
            advertisingTrackers = advertisingTrackers,
            uniqueTrackerDomains = uniqueTrackerDomains,
            uniqueHostnames = uniqueHostnames,
            uniquePorts = uniquePorts,
            portDiversity = portDiversity,
            tls12Connections = tls12Connections,
            tls13Connections = tls13Connections,
            tls13Share = tls13Share,
            avgDnsResponseTimeMs = avgDnsResponseTimeMs,
            maxDnsResponseTimeMs = maxDnsResponseTimeMs,
            connectionBurstRate = connectionBurstRate,
            trafficEntropy = trafficEntropy
        )
    }

    /**
     * Calculate Shannon entropy for a distribution.
     * H = -Σ(p_i * log2(p_i))
     * Normalized to 0-1 range where 1 = maximum entropy (uniform distribution)
     */
    private fun calculateShannonEntropy(distribution: Map<*, Int>): Double {
        if (distribution.isEmpty()) return 0.0
        val total = distribution.values.sum().toDouble()
        if (total == 0.0) return 0.0

        val entropy = distribution.values
            .filter { it > 0 }
            .sumOf { count ->
                val p = count / total
                -p * (ln(p) / ln(2.0))
            }

        // Normalize by max possible entropy (log2 of number of categories)
        val maxEntropy = ln(distribution.size.toDouble()) / ln(2.0)
        return if (maxEntropy > 0) entropy / maxEntropy else 0.0
    }

    /**
     * Calculate burst rate - maximum events per time window.
     * Used for detecting traffic bursts/spikes.
     */
    private fun calculateBurstRate(timestamps: List<Long>, windowMs: Long): Double {
        if (timestamps.isEmpty()) return 0.0
        val sorted = timestamps.sorted()
        var maxCount = 0
        var windowStart = 0

        for (i in sorted.indices) {
            // Move window start forward
            while (sorted[windowStart] < sorted[i] - windowMs) {
                windowStart++
            }
            val count = i - windowStart + 1
            if (count > maxCount) maxCount = count
        }

        // Convert to rate per minute
        return maxCount * (60000.0 / windowMs)
    }

    /**
     * Count TLS versions from TLS handshake observations only.
     * We count ONLY from app.tlsConnections (actual observed TLS Client Hello handshakes)
     * to avoid double-counting with ConnectionInfo entries that may also carry tlsVersion.
     */
    private fun countTlsVersions(app: AppTrafficInfo): Map<TlsVersion, Int> {
        val counts = mutableMapOf<TlsVersion, Int>()

        // Count from TLS handshake observations only (authoritative source)
        app.tlsConnections.forEach { tls ->
            counts[tls.tlsVersion] = (counts[tls.tlsVersion] ?: 0) + 1
        }


        return counts
    }

    /**
     * Collect unique hostnames from SNI and DNS data.
     */
    private fun collectUniqueHostnames(app: AppTrafficInfo): Set<String> {
        val hostnames = mutableSetOf<String>()

        // Add SNI hostnames from connections
        app.connections.forEach { conn ->
            conn.sniHostname?.let { if (it.isNotBlank()) hostnames.add(it) }
            conn.hostname?.let { if (it.isNotBlank()) hostnames.add(it) }
        }

        // Add SNI hostnames from TLS connections
        app.tlsConnections.forEach { tls ->
            tls.sniHostname?.let { if (it.isNotBlank()) hostnames.add(it) }
        }

        // Add domains from DNS queries
        app.dnsQueries.forEach { dns ->
            if (dns.domain.isNotBlank()) hostnames.add(dns.domain)
        }

        return hostnames
    }

    /**
     * Safe division that returns 0 for division by zero.
     */
    private fun safeDivide(numerator: Double, denominator: Double): Double {
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    private fun safeDivide(numerator: Long, denominator: Long): Double {
        return if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    /**
     * Export a single app session to CSV.
     *
     * @param app The app traffic info to export
     * @param sessionDurationMs Session duration in milliseconds
     * @param sessionStartTime Session start timestamp
     * @param outputFile Output CSV file
     * @param category App category
     * @param licenseType License type
     * @param scenario Measurement scenario
     * @param run Run number
     */
    fun exportAppSessionToCsv(
        app: AppTrafficInfo,
        sessionDurationMs: Long,
        sessionStartTime: Long = System.currentTimeMillis() - sessionDurationMs,
        outputFile: File,
        category: String = DEFAULT_CATEGORY,
        licenseType: String = DEFAULT_LICENSE_TYPE,
        scenario: String = DEFAULT_SCENARIO,
        run: Int = DEFAULT_RUN
    ) {
        val metrics = aggregateAppMetrics(app, sessionDurationMs, sessionStartTime, category, licenseType, scenario, run)
        appendToCsv(listOf(metrics), outputFile)
    }

    /**
     * Export multiple app sessions to CSV.
     *
     * @param apps List of app traffic info to export
     * @param sessionDurationMs Session duration in milliseconds
     * @param sessionStartTime Session start timestamp
     * @param outputFile Output CSV file
     * @param metadataProvider Optional function to provide category and license for each app
     * @param scenario Measurement scenario
     * @param run Run number
     */
    fun exportSessionMetricsToCsv(
        apps: List<AppTrafficInfo>,
        sessionDurationMs: Long,
        sessionStartTime: Long = System.currentTimeMillis() - sessionDurationMs,
        outputFile: File,
        metadataProvider: ((AppTrafficInfo) -> Pair<String, String>)? = null,
        scenario: String = DEFAULT_SCENARIO,
        run: Int = DEFAULT_RUN
    ) {
        val metrics = apps.map { app ->
            val (category, licenseType) = metadataProvider?.invoke(app)
                ?: Pair(DEFAULT_CATEGORY, DEFAULT_LICENSE_TYPE)
            aggregateAppMetrics(app, sessionDurationMs, sessionStartTime, category, licenseType, scenario, run)
        }
        appendToCsv(metrics, outputFile)
    }

    // ==================== RAW DATA EXPORT FUNCTIONS ====================

    /**
     * Export raw connection data for all apps.
     * One row per connection - includes IP addresses, ports, hostnames, etc.
     *
     * @param apps List of apps to export
     * @param outputFile Output CSV file
     */
    fun exportRawConnectionsToCsv(
        apps: List<AppTrafficInfo>,
        outputFile: File
    ) {
        val fileExists = outputFile.exists() && outputFile.length() > 0

        FileOutputStream(outputFile, true).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                if (!fileExists) {
                    writer.write(RAW_CONNECTIONS_HEADER)
                    writer.write("\n")
                }

                apps.forEach { app ->
                    app.connections.forEach { conn ->
                        // Get hostname for classification
                        val hostname = conn.sniHostname ?: conn.hostname
                        val classification = TrackerDatabase.classifyConnection(app.packageName, hostname)

                        val row = listOf(
                            escapeCsvField(app.appName),
                            escapeCsvField(app.packageName),
                            conn.timestamp.toString(),
                            escapeCsvField(dateFormat.format(Date(conn.timestamp))),
                            escapeCsvField(conn.destinationAddress),
                            conn.destinationPort.toString(),
                            conn.sourcePort.toString(),
                            conn.protocol.name,
                            conn.bytesSent.toString(),
                            conn.bytesReceived.toString(),
                            conn.packetsSent.toString(),
                            conn.packetsReceived.toString(),
                            escapeCsvField(conn.hostname ?: ""),
                            escapeCsvField(conn.sniHostname ?: ""),
                            escapeCsvField(conn.country ?: ""),
                            conn.isEncrypted.toString(),
                            escapeCsvField(conn.tlsVersion ?: ""),
                            conn.connectionDuration.toString(),
                            classification.isTracker().toString(),
                            escapeCsvField(if (classification.isTracker()) classification.toDisplayName() else (conn.trackerCategory ?: "")),
                            classification.name
                        ).joinToString(",")
                        writer.write(row)
                        writer.write("\n")
                    }
                }
            }
        }
    }

    /**
     * Export raw DNS query data for all apps.
     * One row per DNS query - includes domain, response IPs, response times, etc.
     *
     * @param apps List of apps to export
     * @param outputFile Output CSV file
     */
    fun exportRawDnsQueriesToCsv(
        apps: List<AppTrafficInfo>,
        outputFile: File
    ) {
        val fileExists = outputFile.exists() && outputFile.length() > 0

        FileOutputStream(outputFile, true).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                if (!fileExists) {
                    writer.write(RAW_DNS_HEADER)
                    writer.write("\n")
                }

                apps.forEach { app ->
                    app.dnsQueries.forEach { dns ->
                        val row = listOf(
                            escapeCsvField(app.appName),
                            escapeCsvField(app.packageName),
                            dns.timestamp.toString(),
                            escapeCsvField(dateFormat.format(Date(dns.timestamp))),
                            escapeCsvField(dns.domain),
                            dns.queryType.name,
                            escapeCsvField(dns.responseAddresses.joinToString(";")),
                            dns.responseTime.toString(),
                            dns.responseTtl.toString(),
                            dns.querySize.toString(),
                            dns.responseSize.toString(),
                            dns.responseCode.name,
                            dns.isBlocked.toString(),
                            dns.resolvedVia.name
                        ).joinToString(",")
                        writer.write(row)
                        writer.write("\n")
                    }
                }
            }
        }
    }

    /**
     * Export aggregated destination summary for all apps.
     * One row per unique destination per app - includes connection counts, bytes, etc.
     *
     * @param apps List of apps to export
     * @param outputFile Output CSV file
     */
    fun exportRawDestinationsToCsv(
        apps: List<AppTrafficInfo>,
        outputFile: File
    ) {
        val fileExists = outputFile.exists() && outputFile.length() > 0

        FileOutputStream(outputFile, true).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                if (!fileExists) {
                    writer.write(RAW_DESTINATIONS_HEADER)
                    writer.write("\n")
                }

                apps.forEach { app ->
                    // Group connections by destination
                    val thirdPartyDests = app.getThirdPartyDestinations()
                    val destinationGroups = app.connections.groupBy {
                        Triple(it.destinationAddress, it.destinationPort, it.protocol)
                    }

                    destinationGroups.forEach { (key, connections) ->
                        val (ip, port, protocol) = key
                        val hostname = connections.firstNotNullOfOrNull { it.sniHostname ?: it.hostname } ?: ""
                        val country = connections.firstNotNullOfOrNull { it.country } ?: ""
                        val isEncrypted = connections.any { it.isEncrypted }
                        val isThirdParty = thirdPartyDests.contains(ip)
                        val totalBytes = connections.sumOf { it.totalBytes }

                        val row = listOf(
                            escapeCsvField(app.appName),
                            escapeCsvField(app.packageName),
                            escapeCsvField(ip),
                            escapeCsvField(hostname),
                            port.toString(),
                            protocol.name,
                            connections.size.toString(),
                            totalBytes.toString(),
                            isEncrypted.toString(),
                            isThirdParty.toString(),
                            escapeCsvField(country)
                        ).joinToString(",")
                        writer.write(row)
                        writer.write("\n")
                    }
                }
            }
        }
    }

    /**
     * Export all data (aggregated metrics + raw data) to multiple CSV files.
     * Returns a map of file type to file path.
     *
     * @param apps List of apps to export
     * @param sessionDurationMs Session duration in milliseconds
     * @param sessionStartTime Session start timestamp
     * @param baseFilename Base filename (without extension)
     * @param metadataProvider Optional function to provide category and license for each app
     * @param scenario Measurement scenario
     * @param run Run number
     * @return Map of file type to file path
     */
    fun exportAllDataToCsv(
        apps: List<AppTrafficInfo>,
        sessionDurationMs: Long,
        sessionStartTime: Long = System.currentTimeMillis() - sessionDurationMs,
        baseFilename: String = "echo_export",
        metadataProvider: ((AppTrafficInfo) -> Pair<String, String>)? = null,
        scenario: String = DEFAULT_SCENARIO,
        run: Int = DEFAULT_RUN
    ): Map<String, String> {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir

        val metricsFile = File(externalDir, "${baseFilename}_metrics_$timestamp.csv")
        val connectionsFile = File(externalDir, "${baseFilename}_connections_$timestamp.csv")
        val dnsFile = File(externalDir, "${baseFilename}_dns_$timestamp.csv")
        val destinationsFile = File(externalDir, "${baseFilename}_destinations_$timestamp.csv")

        // Export aggregated metrics
        exportSessionMetricsToCsv(apps, sessionDurationMs, sessionStartTime, metricsFile, metadataProvider, scenario, run)

        // Export raw data
        exportRawConnectionsToCsv(apps, connectionsFile)
        exportRawDnsQueriesToCsv(apps, dnsFile)
        exportRawDestinationsToCsv(apps, destinationsFile)

        return mapOf(
            "metrics" to metricsFile.absolutePath,
            "connections" to connectionsFile.absolutePath,
            "dns" to dnsFile.absolutePath,
            "destinations" to destinationsFile.absolutePath
        )
    }

    /**
     * Escape CSV field to handle commas and quotes.
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains(";")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    /**
     * Write or append metrics to CSV file.
     * Creates header if file is new, appends otherwise.
     */
    private fun appendToCsv(metrics: List<AppSessionMetrics>, outputFile: File) {
        val fileExists = outputFile.exists() && outputFile.length() > 0

        FileOutputStream(outputFile, true).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                // Write header if new file
                if (!fileExists) {
                    writer.write(CSV_HEADER)
                    writer.write("\n")
                }

                // Write data rows
                metrics.forEach { metric ->
                    writer.write(metric.toCsvRow())
                    writer.write("\n")
                }
            }
        }
    }

    /**
     * Get default output file in app's external files directory.
     */
    fun getDefaultOutputFile(filename: String = "echo_measurements.csv"): File {
        val externalDir = context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(externalDir, filename)
    }

    /**
     * Clear/reset the CSV file (remove all data).
     */
    fun clearCsvFile(outputFile: File) {
        if (outputFile.exists()) {
            outputFile.delete()
        }
    }
}

