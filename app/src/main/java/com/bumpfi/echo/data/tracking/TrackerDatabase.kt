package com.bumpfi.echo.data.tracking

/**
 * Database of known trackers, analytics services, and ad networks.
 *
 * Sources for this data (for scientific papers, cite these):
 * - Exodus Privacy: https://exodus-privacy.eu.org/
 * - EasyList/EasyPrivacy: https://easylist.to/
 * - Disconnect Tracking Protection: https://disconnect.me/
 * - AdGuard Tracking Filter: https://adguard.com/
 * - DuckDuckGo Tracker Radar: https://github.com/nickedwards109/tracker-radar
 *
 * Classification follows industry-standard categories:
 * - ANALYTICS: Usage tracking, crash reporting, performance monitoring
 * - ADVERTISING: Ad networks, ad targeting, remarketing
 * - SOCIAL: Social media widgets, login providers
 * - FINGERPRINTING: Device fingerprinting, cross-site tracking
 * - TELEMETRY: App telemetry, diagnostics
 * - CDN: Content delivery networks (not tracking, but third-party)
 * - ESSENTIAL: Required for app functionality (APIs, backends)
 */
object TrackerDatabase {

    enum class TrackerCategory {
        ANALYTICS,
        ADVERTISING,
        SOCIAL,
        FINGERPRINTING,
        TELEMETRY,
        CDN,
        ESSENTIAL,
        UNKNOWN
    }

    data class TrackerInfo(
        val domain: String,
        val name: String,
        val category: TrackerCategory,
        val company: String,
        val description: String = ""
    )

    /**
     * Known tracker domains and their classifications.
     * This is a curated subset - in production, load from JSON or remote source.
     */
    private val trackerDomains: Map<String, TrackerInfo> = mapOf(
        // ===== GOOGLE =====
        "google-analytics.com" to TrackerInfo("google-analytics.com", "Google Analytics", TrackerCategory.ANALYTICS, "Google", "Web and app analytics"),
        "googleanalytics.com" to TrackerInfo("googleanalytics.com", "Google Analytics", TrackerCategory.ANALYTICS, "Google", "Web and app analytics"),
        "analytics.google.com" to TrackerInfo("analytics.google.com", "Google Analytics", TrackerCategory.ANALYTICS, "Google", "Web and app analytics"),
        "firebase.google.com" to TrackerInfo("firebase.google.com", "Firebase Analytics", TrackerCategory.ANALYTICS, "Google", "Mobile app analytics"),
        "firebaseio.com" to TrackerInfo("firebaseio.com", "Firebase", TrackerCategory.ANALYTICS, "Google", "Mobile backend and analytics"),
        "firebaseinstallations.googleapis.com" to TrackerInfo("firebaseinstallations.googleapis.com", "Firebase", TrackerCategory.ANALYTICS, "Google", "App installation tracking"),
        "firebaselogging.googleapis.com" to TrackerInfo("firebaselogging.googleapis.com", "Firebase Logging", TrackerCategory.TELEMETRY, "Google", "Firebase event logging"),
        "play.googleapis.com" to TrackerInfo("play.googleapis.com", "Google Play Services", TrackerCategory.TELEMETRY, "Google", "Play Store telemetry"),
        "crashlytics.com" to TrackerInfo("crashlytics.com", "Crashlytics", TrackerCategory.TELEMETRY, "Google", "Crash reporting"),
        "crashlyticsreports-pa.googleapis.com" to TrackerInfo("crashlyticsreports-pa.googleapis.com", "Crashlytics", TrackerCategory.TELEMETRY, "Google", "Crash reporting upload"),
        "firebaseremoteconfig.googleapis.com" to TrackerInfo("firebaseremoteconfig.googleapis.com", "Firebase Remote Config", TrackerCategory.TELEMETRY, "Google", "Remote configuration"),
        "app-measurement.com" to TrackerInfo("app-measurement.com", "Google App Measurement", TrackerCategory.ANALYTICS, "Google", "Mobile app analytics"),
        "doubleclick.net" to TrackerInfo("doubleclick.net", "DoubleClick", TrackerCategory.ADVERTISING, "Google", "Ad serving and tracking"),
        "googlesyndication.com" to TrackerInfo("googlesyndication.com", "Google AdSense", TrackerCategory.ADVERTISING, "Google", "Ad network"),
        "googleadservices.com" to TrackerInfo("googleadservices.com", "Google Ads", TrackerCategory.ADVERTISING, "Google", "Ad conversion tracking"),
        "googletag.manager.com" to TrackerInfo("googletag.manager.com", "Google Tag Manager", TrackerCategory.ANALYTICS, "Google", "Tag management"),
        "googletagservices.com" to TrackerInfo("googletagservices.com", "Google Tag Services", TrackerCategory.ANALYTICS, "Google", "Tag services"),
        "gstatic.com" to TrackerInfo("gstatic.com", "Google Static", TrackerCategory.CDN, "Google", "Static content CDN"),

        // ===== FACEBOOK/META =====
        "facebook.com" to TrackerInfo("facebook.com", "Facebook", TrackerCategory.SOCIAL, "Meta", "Social network"),
        "fbcdn.net" to TrackerInfo("fbcdn.net", "Facebook CDN", TrackerCategory.CDN, "Meta", "Content delivery"),
        "facebook.net" to TrackerInfo("facebook.net", "Facebook SDK", TrackerCategory.ANALYTICS, "Meta", "Facebook SDK analytics"),
        "fbsbx.com" to TrackerInfo("fbsbx.com", "Facebook", TrackerCategory.SOCIAL, "Meta", "Facebook sandbox"),
        "graph.facebook.com" to TrackerInfo("graph.facebook.com", "Facebook Graph API", TrackerCategory.SOCIAL, "Meta", "Facebook API"),
        "pixel.facebook.com" to TrackerInfo("pixel.facebook.com", "Facebook Pixel", TrackerCategory.ADVERTISING, "Meta", "Conversion tracking"),
        "instagram.com" to TrackerInfo("instagram.com", "Instagram", TrackerCategory.SOCIAL, "Meta", "Social network"),
        "cdninstagram.com" to TrackerInfo("cdninstagram.com", "Instagram CDN", TrackerCategory.CDN, "Meta", "Content delivery"),

        // ===== MICROSOFT =====
        "appcenter.ms" to TrackerInfo("appcenter.ms", "App Center", TrackerCategory.TELEMETRY, "Microsoft", "Crash and analytics"),
        "clarity.ms" to TrackerInfo("clarity.ms", "Microsoft Clarity", TrackerCategory.ANALYTICS, "Microsoft", "Session recording"),
        "bing.com" to TrackerInfo("bing.com", "Bing Ads", TrackerCategory.ADVERTISING, "Microsoft", "Ad network"),
        "msads.net" to TrackerInfo("msads.net", "Microsoft Ads", TrackerCategory.ADVERTISING, "Microsoft", "Ad network"),

        // ===== AMAZON =====
        "amazon-adsystem.com" to TrackerInfo("amazon-adsystem.com", "Amazon Ads", TrackerCategory.ADVERTISING, "Amazon", "Ad network"),
        "amazonwebservices.com" to TrackerInfo("amazonwebservices.com", "AWS", TrackerCategory.CDN, "Amazon", "Cloud services"),
        "cloudfront.net" to TrackerInfo("cloudfront.net", "CloudFront", TrackerCategory.CDN, "Amazon", "CDN"),
        "amazonaws.com" to TrackerInfo("amazonaws.com", "AWS", TrackerCategory.CDN, "Amazon", "Cloud services"),

        // ===== ANALYTICS PROVIDERS =====
        "mixpanel.com" to TrackerInfo("mixpanel.com", "Mixpanel", TrackerCategory.ANALYTICS, "Mixpanel", "Product analytics"),
        "amplitude.com" to TrackerInfo("amplitude.com", "Amplitude", TrackerCategory.ANALYTICS, "Amplitude", "Product analytics"),
        "segment.io" to TrackerInfo("segment.io", "Segment", TrackerCategory.ANALYTICS, "Twilio", "Customer data platform"),
        "segment.com" to TrackerInfo("segment.com", "Segment", TrackerCategory.ANALYTICS, "Twilio", "Customer data platform"),
        "braze.com" to TrackerInfo("braze.com", "Braze", TrackerCategory.ANALYTICS, "Braze", "Customer engagement"),
        "branch.io" to TrackerInfo("branch.io", "Branch", TrackerCategory.ANALYTICS, "Branch", "Deep linking and attribution"),
        "adjust.com" to TrackerInfo("adjust.com", "Adjust", TrackerCategory.ANALYTICS, "Adjust", "Mobile attribution"),
        "appsflyer.com" to TrackerInfo("appsflyer.com", "AppsFlyer", TrackerCategory.ANALYTICS, "AppsFlyer", "Mobile attribution"),
        "kochava.com" to TrackerInfo("kochava.com", "Kochava", TrackerCategory.ANALYTICS, "Kochava", "Mobile attribution"),
        "flurry.com" to TrackerInfo("flurry.com", "Flurry", TrackerCategory.ANALYTICS, "Yahoo", "Mobile analytics"),
        "localytics.com" to TrackerInfo("localytics.com", "Localytics", TrackerCategory.ANALYTICS, "Upland", "Mobile analytics"),
        "newrelic.com" to TrackerInfo("newrelic.com", "New Relic", TrackerCategory.TELEMETRY, "New Relic", "Application monitoring"),
        "bugsnag.com" to TrackerInfo("bugsnag.com", "Bugsnag", TrackerCategory.TELEMETRY, "SmartBear", "Error monitoring"),
        "sentry.io" to TrackerInfo("sentry.io", "Sentry", TrackerCategory.TELEMETRY, "Sentry", "Error monitoring"),
        "instabug.com" to TrackerInfo("instabug.com", "Instabug", TrackerCategory.TELEMETRY, "Instabug", "Bug reporting"),
        "hotjar.com" to TrackerInfo("hotjar.com", "Hotjar", TrackerCategory.ANALYTICS, "Hotjar", "Session recording"),
        "heap.io" to TrackerInfo("heap.io", "Heap", TrackerCategory.ANALYTICS, "Heap", "Product analytics"),
        "fullstory.com" to TrackerInfo("fullstory.com", "FullStory", TrackerCategory.ANALYTICS, "FullStory", "Session replay"),
        "optimizely.com" to TrackerInfo("optimizely.com", "Optimizely", TrackerCategory.ANALYTICS, "Optimizely", "A/B testing"),
        "clevertap.com" to TrackerInfo("clevertap.com", "CleverTap", TrackerCategory.ANALYTICS, "CleverTap", "Customer engagement"),
        "onesignal.com" to TrackerInfo("onesignal.com", "OneSignal", TrackerCategory.ANALYTICS, "OneSignal", "Push notifications"),

        // ===== AD NETWORKS =====
        "moat.com" to TrackerInfo("moat.com", "Moat", TrackerCategory.ADVERTISING, "Oracle", "Ad verification"),
        "mopub.com" to TrackerInfo("mopub.com", "MoPub", TrackerCategory.ADVERTISING, "AppLovin", "Mobile ad network"),
        "unity3d.com" to TrackerInfo("unity3d.com", "Unity Ads", TrackerCategory.ADVERTISING, "Unity", "Mobile ad network"),
        "unityads.unity3d.com" to TrackerInfo("unityads.unity3d.com", "Unity Ads", TrackerCategory.ADVERTISING, "Unity", "Mobile ad network"),
        "applovin.com" to TrackerInfo("applovin.com", "AppLovin", TrackerCategory.ADVERTISING, "AppLovin", "Mobile ad network"),
        "vungle.com" to TrackerInfo("vungle.com", "Vungle", TrackerCategory.ADVERTISING, "Liftoff", "Video ad network"),
        "inmobi.com" to TrackerInfo("inmobi.com", "InMobi", TrackerCategory.ADVERTISING, "InMobi", "Mobile ad network"),
        "chartboost.com" to TrackerInfo("chartboost.com", "Chartboost", TrackerCategory.ADVERTISING, "Zynga", "Mobile ad network"),
        "ironsrc.com" to TrackerInfo("ironsrc.com", "IronSource", TrackerCategory.ADVERTISING, "Unity", "Mobile ad platform"),
        "ironsource.com" to TrackerInfo("ironsource.com", "IronSource", TrackerCategory.ADVERTISING, "Unity", "Mobile ad platform"),
        "adcolony.com" to TrackerInfo("adcolony.com", "AdColony", TrackerCategory.ADVERTISING, "Digital Turbine", "Mobile video ads"),
        "tapjoy.com" to TrackerInfo("tapjoy.com", "Tapjoy", TrackerCategory.ADVERTISING, "IronSource", "Mobile ad network"),
        "startapp.com" to TrackerInfo("startapp.com", "StartApp", TrackerCategory.ADVERTISING, "StartApp", "Mobile ad network"),
        "fyber.com" to TrackerInfo("fyber.com", "Fyber", TrackerCategory.ADVERTISING, "Digital Turbine", "Ad mediation"),
        "pubmatic.com" to TrackerInfo("pubmatic.com", "PubMatic", TrackerCategory.ADVERTISING, "PubMatic", "Ad exchange"),
        "criteo.com" to TrackerInfo("criteo.com", "Criteo", TrackerCategory.ADVERTISING, "Criteo", "Retargeting"),
        "taboola.com" to TrackerInfo("taboola.com", "Taboola", TrackerCategory.ADVERTISING, "Taboola", "Content recommendation"),
        "outbrain.com" to TrackerInfo("outbrain.com", "Outbrain", TrackerCategory.ADVERTISING, "Outbrain", "Content recommendation"),
        "adnxs.com" to TrackerInfo("adnxs.com", "Xandr", TrackerCategory.ADVERTISING, "Microsoft", "Ad exchange"),
        "rubiconproject.com" to TrackerInfo("rubiconproject.com", "Magnite", TrackerCategory.ADVERTISING, "Magnite", "Ad exchange"),
        "openx.net" to TrackerInfo("openx.net", "OpenX", TrackerCategory.ADVERTISING, "OpenX", "Ad exchange"),
        "bidswitch.net" to TrackerInfo("bidswitch.net", "Bidswitch", TrackerCategory.ADVERTISING, "IPONWEB", "Ad exchange"),
        "smartadserver.com" to TrackerInfo("smartadserver.com", "Smart AdServer", TrackerCategory.ADVERTISING, "Equativ", "Ad server"),
        "smaato.net" to TrackerInfo("smaato.net", "Smaato", TrackerCategory.ADVERTISING, "Verve", "Mobile ad exchange"),
        "liftoff.io" to TrackerInfo("liftoff.io", "Liftoff", TrackerCategory.ADVERTISING, "Liftoff", "Mobile DSP"),

        // ===== FINGERPRINTING =====
        "fingerprintjs.com" to TrackerInfo("fingerprintjs.com", "FingerprintJS", TrackerCategory.FINGERPRINTING, "FingerprintJS", "Device fingerprinting"),
        "ioam.de" to TrackerInfo("ioam.de", "IOAM", TrackerCategory.FINGERPRINTING, "AGOF", "Audience measurement"),
        "permutive.com" to TrackerInfo("permutive.com", "Permutive", TrackerCategory.FINGERPRINTING, "Permutive", "Audience platform"),

        // ===== CDNs (Third-party but not tracking) =====
        "akamai.net" to TrackerInfo("akamai.net", "Akamai", TrackerCategory.CDN, "Akamai", "CDN"),
        "akamaihd.net" to TrackerInfo("akamaihd.net", "Akamai", TrackerCategory.CDN, "Akamai", "CDN"),
        "akamaized.net" to TrackerInfo("akamaized.net", "Akamai", TrackerCategory.CDN, "Akamai", "CDN"),
        "cloudflare.com" to TrackerInfo("cloudflare.com", "Cloudflare", TrackerCategory.CDN, "Cloudflare", "CDN and security"),
        "cloudflare.net" to TrackerInfo("cloudflare.net", "Cloudflare", TrackerCategory.CDN, "Cloudflare", "CDN"),
        "fastly.net" to TrackerInfo("fastly.net", "Fastly", TrackerCategory.CDN, "Fastly", "CDN"),
        "edgecastcdn.net" to TrackerInfo("edgecastcdn.net", "Edgecast", TrackerCategory.CDN, "Verizon", "CDN"),
        "azureedge.net" to TrackerInfo("azureedge.net", "Azure CDN", TrackerCategory.CDN, "Microsoft", "CDN"),
        "jsdelivr.net" to TrackerInfo("jsdelivr.net", "jsDelivr", TrackerCategory.CDN, "jsDelivr", "Open source CDN"),

        // ===== SOCIAL LOGINS =====
        "twitter.com" to TrackerInfo("twitter.com", "Twitter/X", TrackerCategory.SOCIAL, "X Corp", "Social network"),
        "twimg.com" to TrackerInfo("twimg.com", "Twitter CDN", TrackerCategory.CDN, "X Corp", "Content delivery"),
        "linkedin.com" to TrackerInfo("linkedin.com", "LinkedIn", TrackerCategory.SOCIAL, "Microsoft", "Professional network"),
        "licdn.com" to TrackerInfo("licdn.com", "LinkedIn CDN", TrackerCategory.CDN, "Microsoft", "Content delivery"),
        "apple.com" to TrackerInfo("apple.com", "Apple", TrackerCategory.ESSENTIAL, "Apple", "Apple services"),
        "icloud.com" to TrackerInfo("icloud.com", "iCloud", TrackerCategory.ESSENTIAL, "Apple", "Cloud services"),

        // ===== CHINESE TRACKERS =====
        "umeng.com" to TrackerInfo("umeng.com", "Umeng", TrackerCategory.ANALYTICS, "Alibaba", "Mobile analytics"),
        "aliyuncs.com" to TrackerInfo("aliyuncs.com", "Aliyun", TrackerCategory.CDN, "Alibaba", "Cloud services"),
        "tencent.com" to TrackerInfo("tencent.com", "Tencent", TrackerCategory.ANALYTICS, "Tencent", "Analytics"),
        "qq.com" to TrackerInfo("qq.com", "QQ", TrackerCategory.SOCIAL, "Tencent", "Social network"),
        "weixin.qq.com" to TrackerInfo("weixin.qq.com", "WeChat", TrackerCategory.SOCIAL, "Tencent", "Messaging"),
        "baidu.com" to TrackerInfo("baidu.com", "Baidu", TrackerCategory.ANALYTICS, "Baidu", "Analytics"),
        "hm.baidu.com" to TrackerInfo("hm.baidu.com", "Baidu Analytics", TrackerCategory.ANALYTICS, "Baidu", "Web analytics"),
        "bytedance.com" to TrackerInfo("bytedance.com", "ByteDance", TrackerCategory.ANALYTICS, "ByteDance", "Analytics"),
        "tiktokv.com" to TrackerInfo("tiktokv.com", "TikTok", TrackerCategory.SOCIAL, "ByteDance", "Social video"),
        "musical.ly" to TrackerInfo("musical.ly", "TikTok", TrackerCategory.SOCIAL, "ByteDance", "Social video"),

        // ===== PAYMENT/ESSENTIAL =====
        "stripe.com" to TrackerInfo("stripe.com", "Stripe", TrackerCategory.ESSENTIAL, "Stripe", "Payments"),
        "paypal.com" to TrackerInfo("paypal.com", "PayPal", TrackerCategory.ESSENTIAL, "PayPal", "Payments"),
        "braintreegateway.com" to TrackerInfo("braintreegateway.com", "Braintree", TrackerCategory.ESSENTIAL, "PayPal", "Payments")
    )

    /**
     * Known first-party domain mappings for popular apps.
     * Maps package names to their legitimate first-party domains.
     */
    private val appFirstPartyDomains: Map<String, Set<String>> = mapOf(
        // Google apps
        "com.google" to setOf("google.com", "googleapis.com", "gstatic.com", "youtube.com", "googlevideo.com", "ggpht.com", "googleusercontent.com"),
        "com.android" to setOf("google.com", "googleapis.com", "android.com"),

        // Meta apps
        "com.facebook" to setOf("facebook.com", "fbcdn.net", "fb.com", "fbsbx.com", "instagram.com", "cdninstagram.com"),
        "com.instagram" to setOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net"),
        "com.whatsapp" to setOf("whatsapp.com", "whatsapp.net", "facebook.com"),

        // Microsoft apps
        "com.microsoft" to setOf("microsoft.com", "msftauth.net", "live.com", "office.com", "office365.com", "azure.com", "windows.net"),
        "com.skype" to setOf("skype.com", "microsoft.com", "live.com"),

        // Amazon apps
        "com.amazon" to setOf("amazon.com", "amazonaws.com", "cloudfront.net", "ssl-images-amazon.com"),

        // Twitter/X
        "com.twitter" to setOf("twitter.com", "twimg.com", "t.co", "x.com"),

        // Spotify
        "com.spotify" to setOf("spotify.com", "scdn.co", "spotifycdn.com"),

        // Netflix
        "com.netflix" to setOf("netflix.com", "nflximg.net", "nflxvideo.net", "nflxso.net"),

        // Uber
        "com.ubercab" to setOf("uber.com", "ubereats.com"),

        // Snapchat
        "com.snapchat" to setOf("snapchat.com", "snap.com", "snapkit.com", "sc-cdn.net"),

        // TikTok
        "com.zhiliaoapp.musically" to setOf("tiktok.com", "tiktokcdn.com", "bytedance.com", "musical.ly"),
        "com.ss.android.ugc.trill" to setOf("tiktok.com", "tiktokcdn.com", "bytedance.com"),

        // LinkedIn
        "com.linkedin" to setOf("linkedin.com", "licdn.com"),

        // Pinterest
        "com.pinterest" to setOf("pinterest.com", "pinimg.com"),

        // Telegram
        "org.telegram" to setOf("telegram.org", "telegram.me", "t.me")
    )

    /**
     * Get tracker info for a domain.
     */
    fun getTrackerInfo(domain: String): TrackerInfo? {
        return findTrackerMatch(domain.lowercase().trim())
    }

    /**
     * Check if a domain is a tracking domain (excludes CDN and ESSENTIAL).
     */
    fun isTrackingDomain(domain: String): Boolean {
        val info = getTrackerInfo(domain) ?: return false
        return info.category in listOf(
            TrackerCategory.ANALYTICS,
            TrackerCategory.ADVERTISING,
            TrackerCategory.FINGERPRINTING,
            TrackerCategory.TELEMETRY
        )
    }

    /**
     * Check if a domain is first-party for a given app package.
     */
    fun isFirstPartyDomain(packageName: String, domain: String): Boolean {
        val normalizedDomain = domain.lowercase().trim()

        // Check exact app mappings
        val packagePrefix = packageName.split(".").take(2).joinToString(".")
        val firstPartyDomains = appFirstPartyDomains[packagePrefix]
            ?: appFirstPartyDomains[packageName]

        if (firstPartyDomains != null) {
            return firstPartyDomains.any { firstParty ->
                normalizedDomain == firstParty || normalizedDomain.endsWith(".$firstParty")
            }
        }

        // Fallback to heuristic: infer from package name
        val inferredDomains = inferDomainsFromPackage(packageName)
        return inferredDomains.any { inferred ->
            normalizedDomain == inferred || normalizedDomain.endsWith(".$inferred")
        }
    }

    /**
     * Classify a connection as first-party, third-party tracker, or third-party non-tracker.
     *
     * IMPORTANT: Known tracking domains (analytics, advertising, fingerprinting, telemetry)
     * are classified as trackers EVEN if they match an app's first-party domain pattern.
     * This prevents broad first-party patterns (e.g. "googleapis.com" for Google apps) from
     * hiding tracker connections like "firebaseinstallations.googleapis.com".
     */
    fun classifyConnection(packageName: String, hostname: String?): ConnectionClassification {
        if (hostname.isNullOrBlank()) {
            return ConnectionClassification.UNKNOWN
        }

        val normalizedHostname = hostname.lowercase().trim()

        // 1. Check if known tracker FIRST — trackers take priority over first-party matching
        //    This ensures e.g. app-measurement.com or firebaseinstallations.googleapis.com
        //    are correctly flagged even for Google's own apps.
        val trackerInfo = getTrackerInfo(normalizedHostname)
        if (trackerInfo != null) {
            return when (trackerInfo.category) {
                TrackerCategory.ANALYTICS -> ConnectionClassification.TRACKER_ANALYTICS
                TrackerCategory.ADVERTISING -> ConnectionClassification.TRACKER_ADVERTISING
                TrackerCategory.FINGERPRINTING -> ConnectionClassification.TRACKER_FINGERPRINTING
                TrackerCategory.TELEMETRY -> ConnectionClassification.TRACKER_TELEMETRY
                // CDN, ESSENTIAL, SOCIAL: if first-party, treat as first-party; otherwise third-party
                TrackerCategory.CDN -> {
                    if (isFirstPartyDomain(packageName, normalizedHostname))
                        ConnectionClassification.FIRST_PARTY
                    else
                        ConnectionClassification.THIRD_PARTY_CDN
                }
                TrackerCategory.ESSENTIAL -> {
                    if (isFirstPartyDomain(packageName, normalizedHostname))
                        ConnectionClassification.FIRST_PARTY
                    else
                        ConnectionClassification.THIRD_PARTY_ESSENTIAL
                }
                TrackerCategory.SOCIAL -> {
                    if (isFirstPartyDomain(packageName, normalizedHostname))
                        ConnectionClassification.FIRST_PARTY
                    else
                        ConnectionClassification.THIRD_PARTY_SOCIAL
                }
                TrackerCategory.UNKNOWN -> {
                    if (isFirstPartyDomain(packageName, normalizedHostname))
                        ConnectionClassification.FIRST_PARTY
                    else
                        ConnectionClassification.THIRD_PARTY_UNKNOWN
                }
            }
        }

        // 2. Check if first-party (no tracker match found)
        if (isFirstPartyDomain(packageName, normalizedHostname)) {
            return ConnectionClassification.FIRST_PARTY
        }

        return ConnectionClassification.THIRD_PARTY_UNKNOWN
    }

    /**
     * Find matching tracker entry for a domain (handles subdomains).
     */
    private fun findTrackerMatch(domain: String): TrackerInfo? {
        // Direct match
        trackerDomains[domain]?.let { return it }

        // Check parent domains
        val parts = domain.split(".")
        for (i in 1 until parts.size) {
            val parentDomain = parts.drop(i).joinToString(".")
            trackerDomains[parentDomain]?.let { return it }
        }

        return null
    }

    /**
     * Infer expected domains from package name.
     */
    private fun inferDomainsFromPackage(packageName: String): Set<String> {
        val parts = packageName.lowercase().split(".")
        val domains = mutableSetOf<String>()

        if (parts.size >= 2) {
            val tld = parts[0]
            val company = parts[1]

            // Standard patterns
            if (tld in listOf("com", "org", "net", "io", "co")) {
                domains.add("$company.$tld")
            }

            // Also try company name alone for fuzzy matching
            domains.add(company)
        }

        return domains
    }

}

/**
 * Connection classification result.
 */
enum class ConnectionClassification {
    FIRST_PARTY,
    TRACKER_ANALYTICS,
    TRACKER_ADVERTISING,
    TRACKER_FINGERPRINTING,
    TRACKER_TELEMETRY,
    THIRD_PARTY_SOCIAL,
    THIRD_PARTY_CDN,
    THIRD_PARTY_ESSENTIAL,
    THIRD_PARTY_UNKNOWN,
    UNKNOWN;

    fun isTracker(): Boolean = this in listOf(
        TRACKER_ANALYTICS,
        TRACKER_ADVERTISING,
        TRACKER_FINGERPRINTING,
        TRACKER_TELEMETRY
    )

    fun isThirdParty(): Boolean = this != FIRST_PARTY && this != UNKNOWN

    fun toDisplayName(): String = when (this) {
        FIRST_PARTY -> "First Party"
        TRACKER_ANALYTICS -> "Analytics Tracker"
        TRACKER_ADVERTISING -> "Advertising Tracker"
        TRACKER_FINGERPRINTING -> "Fingerprinting"
        TRACKER_TELEMETRY -> "Telemetry"
        THIRD_PARTY_SOCIAL -> "Social Media"
        THIRD_PARTY_CDN -> "CDN"
        THIRD_PARTY_ESSENTIAL -> "Essential Service"
        THIRD_PARTY_UNKNOWN -> "Third Party (Unknown)"
        UNKNOWN -> "Unknown"
    }
}

