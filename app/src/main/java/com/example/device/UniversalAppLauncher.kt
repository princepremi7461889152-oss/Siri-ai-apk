package com.example.device

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

data class LaunchableApp(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val normalizedName: String,
    val compactName: String,
    val phoneticKey: String,
    val tokens: List<String>,
    val isSystemApp: Boolean
)

sealed class AppLaunchOutcome {
    data class Launched(
        val appName: String,
        val packageName: String,
        val message: String
    ) : AppLaunchOutcome()

    data class DisambiguationNeeded(
        val query: String,
        val candidates: List<LaunchableApp>,
        val message: String
    ) : AppLaunchOutcome()

    data class NotFound(
        val query: String,
        val message: String
    ) : AppLaunchOutcome()

    data class LaunchFailed(
        val appName: String,
        val packageName: String,
        val message: String
    ) : AppLaunchOutcome()
}

/**
 * Universal App Launcher that dynamically discovers all launchable Android applications
 * via PackageManager, listens for package install/uninstall/update broadcasts, maintains
 * a local searchable index, and matches English, Hinglish, Hindi Devanagari, phonetic,
 * and misspelled voice queries with disambiguation support.
 */
class UniversalAppLauncher(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val packageManager: PackageManager = appContext.packageManager

    private val _appIndex = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val appIndex: StateFlow<List<LaunchableApp>> = _appIndex.asStateFlow()

    @Volatile
    private var lastRefreshTimeMs: Long = 0L

    @Volatile
    private var receiverRegistered: Boolean = false

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_REPLACED ||
                action == Intent.ACTION_PACKAGE_CHANGED ||
                action == Intent.ACTION_PACKAGE_FULLY_REMOVED
            ) {
                Log.d(TAG, "Package change detected ($action); refreshing universal app index")
                refreshIndex()
            }
        }
    }

    init {
        registerPackageMonitor()
        refreshIndex()
    }

    private fun registerPackageMonitor() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                packageChangeReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not register package monitor receiver", e)
        }
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering package monitor receiver", e)
        } finally {
            receiverRegistered = false
        }
    }

    /**
     * Queries PackageManager for all launchable activities (system + user-installed)
     * using Android 11+ / Android 13+ compatible APIs and rebuilds the local index.
     */
    @Synchronized
    fun refreshIndex(): List<LaunchableApp> {
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved: List<ResolveInfo> = queryIntentActivitiesCompat(mainIntent)

        val selfPkg = appContext.packageName
        val indexed = resolved.mapNotNull { info ->
            val actInfo = info.activityInfo ?: return@mapNotNull null
            val pkg = actInfo.packageName ?: return@mapNotNull null
            val actName = actInfo.name ?: return@mapNotNull null

            val rawLabel = try {
                info.loadLabel(packageManager)?.toString()?.trim()
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() }
                ?: try {
                    actInfo.loadLabel(packageManager)?.toString()?.trim()
                } catch (_: Exception) {
                    null
                }?.takeIf { it.isNotBlank() }
                ?: pkg

            val norm = normalizeLabel(rawLabel)
            val compact = norm.replace(" ", "")
            val tokens = norm.split(" ").filter { it.isNotBlank() }
            val isSystem = try {
                val appFlags = actInfo.applicationInfo?.flags ?: 0
                (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (_: Exception) {
                false
            }

            LaunchableApp(
                appName = rawLabel,
                packageName = pkg,
                activityName = actName,
                normalizedName = norm,
                compactName = compact,
                phoneticKey = buildPhoneticKey(compact),
                tokens = tokens,
                isSystemApp = isSystem
            )
        }
            .filter { it.packageName != selfPkg }
            .distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedBy { it.appName.lowercase() }

        _appIndex.value = indexed
        lastRefreshTimeMs = System.currentTimeMillis()
        return indexed
    }

    fun getInstalledApps(forceRefresh: Boolean = false): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        val current = _appIndex.value
        return if (forceRefresh || current.isEmpty() || (now - lastRefreshTimeMs) > CACHE_TTL_MS) {
            refreshIndex()
        } else {
            current
        }
    }

    fun getInstalledAppCount(): Int = getInstalledApps().size

    /**
     * Searches the local application index and returns ranked matching apps.
     */
    fun searchApps(rawQuery: String, forceRefresh: Boolean = false): List<LaunchableApp> {
        val normalized = normalizeAppTarget(rawQuery)
        if (normalized.isBlank()) return emptyList()
        val apps = getInstalledApps(forceRefresh = forceRefresh)
        return scoreAndRankApps(normalized, apps).map { it.first }
    }

    /**
     * Resolves a spoken/typed app target (English, Hinglish, Hindi Devanagari, alias,
     * misspelled name, or package name) and launches it via PackageManager & Android Intents.
     */
    fun openApp(rawTarget: String): AppLaunchOutcome {
        val trimmed = rawTarget.trim()
        if (trimmed.isBlank()) {
            return AppLaunchOutcome.NotFound(rawTarget, "Please tell me which app you want to open.")
        }

        val normalized = normalizeAppTarget(trimmed)
        val compact = normalized.replace(" ", "")

        // 1. Direct package name launch (e.g. "com.whatsapp")
        if (trimmed.contains('.') && !trimmed.contains(' ')) {
            val directOutcome = launchPackageOrComponent(trimmed, null, trimmed)
            if (directOutcome != null) return directOutcome
        }

        // 2. Settings deep-intent shortcut (settings has no single uniform launcher label across OEMs)
        if (normalized == "settings" || normalized == "setting") {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStartActivity(settingsIntent)) {
                return AppLaunchOutcome.Launched("Settings", "com.android.settings", "Opening Settings.")
            }
        }

        // 3. Dynamic Universal Index search (covers all installed third-party and system launcher apps)
        var apps = getInstalledApps(forceRefresh = false)
        var scored = scoreAndRankApps(normalized, apps)

        if (scored.isEmpty()) {
            // Refresh index once immediately in case an app was just installed before broadcast arrived
            apps = getInstalledApps(forceRefresh = true)
            scored = scoreAndRankApps(normalized, apps)
        }

        if (scored.isNotEmpty()) {
            val top = scored.first()
            val second = scored.getOrNull(1)

            // If multiple distinct packages match with comparable high confidence and no clear single winner,
            // ask the user which app they want to open.
            val distinctPkgCandidates = scored.distinctBy { it.first.packageName }
            if (distinctPkgCandidates.size >= 2) {
                val bestScore = distinctPkgCandidates[0].second
                val runnerUpScore = distinctPkgCandidates[1].second
                val isExactUniqueWinner = bestScore >= 0.99 && runnerUpScore < 0.99
                val hasClearLead = (bestScore - runnerUpScore) >= 0.12
                if (!isExactUniqueWinner && !hasClearLead) {
                    val candidates = distinctPkgCandidates
                        .filter { bestScore - it.second < 0.14 }
                        .take(4)
                        .map { it.first }
                    if (candidates.size >= 2) {
                        val namesList = formatCandidateList(candidates.map { it.appName })
                        return AppLaunchOutcome.DisambiguationNeeded(
                            query = trimmed,
                            candidates = candidates,
                            message = "I found multiple matching apps: $namesList. Which one would you like to open?"
                        )
                    }
                }
            }

            // Launch the single best matched app
            val chosen = top.first
            val outcome = launchPackageOrComponent(chosen.packageName, chosen.activityName, chosen.appName)
            if (outcome != null) return outcome

            // If top failed due to stale entry or missing launch intent, try next distinct candidate if close
            if (second != null && (top.second - second.second) < 0.15) {
                launchPackageOrComponent(second.first.packageName, second.first.activityName, second.first.appName)
                    ?.let { return it }
            }
        }

        // 4. Well-known alias package fallback (for apps whose package exists even if filtered or OEM-renamed)
        val candidatePackages = aliasPackageCandidates[normalized] ?: aliasPackageCandidates[compact]
        if (candidatePackages != null) {
            for (pkg in candidatePackages) {
                launchPackageOrComponent(pkg, null, displayNameFor(normalized, trimmed))?.let {
                    if (it is AppLaunchOutcome.Launched) return it
                }
            }
        }

        // 5. Standard Android implicit category/action fallbacks for core system utilities
        when (normalized) {
            "camera" -> return openCameraFallback()
            "gallery", "photos" -> return openGalleryFallback()
            "calculator" -> return openCalculatorFallback()
            "clock", "alarm" -> return openClockFallback()
            "browser", "internet" -> {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (tryStartActivity(browserIntent)) {
                    return AppLaunchOutcome.Launched("Browser", "android.intent.category.APP_BROWSER", "Opening Browser.")
                }
            }
        }

        return AppLaunchOutcome.NotFound(
            query = trimmed,
            message = "I couldn't find $trimmed installed on your device."
        )
    }

    /**
     * Launches a specific chosen application (e.g., after user disambiguation).
     */
    fun launchSpecificApp(packageName: String, activityName: String?, appName: String): AppLaunchOutcome {
        return launchPackageOrComponent(packageName, activityName, appName)
            ?: AppLaunchOutcome.LaunchFailed(
                appName = appName,
                packageName = packageName,
                message = "Unable to launch $appName because its launch activity is unavailable or restricted."
            )
    }

    fun normalizeAppTarget(rawTarget: String): String {
        var cleaned = rawTarget.trim().lowercase()
            // Remove Hindi/Hinglish/English conversational preambles
            .replace(
                Regex(
                    """^(?:mere\s+phone\s+mein|mere\s+mobile\s+mein|mere\s+device\s+mein|is\s+phone\s+mein|phone\s+mein|mobile\s+mein|device\s+mein|phone\s+me|mobile\s+me|on\s+my\s+phone|in\s+my\s+phone|on\s+this\s+phone|in\s+phone|mere\s+liye|for\s+me|मेरे\s+फोन\s+में|मेरे\s+मोबाइल\s+में|फोन\s+में|मोबाइल\s+में|मेरे\s+लिए)\s+"""
                ),
                ""
            )
            .replace(
                Regex("""^(?:the|a|an|my|mera|meri|mere|मेरा|मेरी|मेरे|please|zara|ज़रा|abhi|अभी|app|application|ऐप|एप्लीकेशन)\s+"""),
                ""
            )
            .replace(
                Regex("""\s+(?:app|application|ko|please|ऐप|एप्लीकेशन|को|प्लीज)$"""),
                ""
            )
            .trim()

        // Strip repeated leading filler like "my" after "phone mein" if stacked
        cleaned = cleaned
            .replace(Regex("""^(?:the|my|mera|meri|mere|मेरा|मेरी|मेरे)\s+"""), "")
            .replace(Regex("""\s+(?:app|application|ko|ऐप|को)$"""), "")
            .trim()

        // Check curated Hindi/STT dictionary first
        hindiAndPhoneticAliases[rawTarget.trim().lowercase()]?.let { return it }
        hindiAndPhoneticAliases[cleaned]?.let { return it }

        // If text still contains Devanagari characters, transliterate dynamically to Latin
        if (cleaned.any { it in '\u0900'..'\u097F' }) {
            val transliterated = normalizeLabel(transliterateDevanagari(cleaned))
            hindiAndPhoneticAliases[transliterated]?.let { return it }
            if (transliterated.isNotBlank()) return transliterated
        }

        return normalizeLabel(cleaned)
    }

    private fun scoreAndRankApps(
        normalizedQuery: String,
        apps: List<LaunchableApp>
    ): List<Pair<LaunchableApp, Double>> {
        if (normalizedQuery.isBlank() || apps.isEmpty()) return emptyList()

        val queryCompact = normalizedQuery.replace(" ", "")
        val queryPhonetic = buildPhoneticKey(queryCompact)
        val queryTokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        val aliasPackages = (aliasPackageCandidates[normalizedQuery] ?: aliasPackageCandidates[queryCompact]).orEmpty()

        val results = mutableListOf<Pair<LaunchableApp, Double>>()

        for (app in apps) {
            var score = 0.0

            // 1. Exact label match
            if (app.normalizedName == normalizedQuery) {
                score = 1.00
            }
            // 2. Exact compact (space-insensitive) match (e.g. "whats app" -> "WhatsApp")
            else if (app.compactName == queryCompact) {
                score = 0.99
            }
            // 3. Well-known alias package match (e.g. "yt" -> com.google.android.youtube)
            else if (app.packageName in aliasPackages) {
                val rankIndex = aliasPackages.indexOf(app.packageName)
                score = (0.97 - rankIndex * 0.02).coerceAtLeast(0.90)
            }
            // 4. Whole token match (e.g. "amazon" matches "Amazon Shopping" and "Amazon Prime Video")
            else if (app.tokens.any { it == normalizedQuery }) {
                val startsBonus = if (app.normalizedName.startsWith(normalizedQuery)) 0.03 else 0.0
                val brevityBonus = (1.0 / (app.tokens.size.coerceAtLeast(1) + 2)).coerceAtMost(0.04)
                score = 0.87 + startsBonus + brevityBonus
            }
            // 5. All query tokens appear as prefixes/words in app tokens (e.g. "prime video" -> "Amazon Prime Video")
            else if (queryTokens.size >= 2 && queryTokens.all { qt -> app.tokens.any { at -> at == qt || at.startsWith(qt) } }) {
                score = 0.88
            }
            // 6. Prefix match on normalized or compact label
            else if (queryCompact.length >= 3 && (app.normalizedName.startsWith(normalizedQuery) || app.compactName.startsWith(queryCompact))) {
                val ratio = queryCompact.length.toDouble() / app.compactName.length.coerceAtLeast(1)
                score = 0.83 + (ratio * 0.08)
            }
            // 7. Package name segment match (e.g. "telegram" -> org.telegram.messenger)
            else if (queryCompact.length >= 3 && app.packageName.lowercase().split('.').any { it == queryCompact }) {
                score = 0.89
            }
            // 8. Phonetic skeleton match (e.g. "kainva" -> "Canva", "fotos" -> "Photos", "crome" -> "Chrome")
            else if (queryPhonetic.length >= 3 && app.phoneticKey == queryPhonetic) {
                score = 0.86
            }
            // 9. Substring match for >= 3 chars
            else if (queryCompact.length >= 3 && (app.normalizedName.contains(normalizedQuery) || app.compactName.contains(queryCompact))) {
                score = 0.78
            }
            // 10. Fuzzy Levenshtein similarity across full compact label, individual tokens, or phonetic key
            else if (queryCompact.length >= 3) {
                val fullDist = levenshteinDistance(queryCompact, app.compactName)
                val fullMaxLen = max(queryCompact.length, app.compactName.length)
                val fullSim = 1.0 - (fullDist.toDouble() / fullMaxLen)

                val phoneticDist = if (queryPhonetic.length >= 3 && app.phoneticKey.length >= 3) {
                    levenshteinDistance(queryPhonetic, app.phoneticKey)
                } else Int.MAX_VALUE
                val phoneticSim = if (phoneticDist < Int.MAX_VALUE) {
                    1.0 - (phoneticDist.toDouble() / max(queryPhonetic.length, app.phoneticKey.length))
                } else 0.0

                val bestTokenSim = app.tokens.maxOfOrNull { token ->
                    if (token.length < 3) 0.0 else {
                        val d = levenshteinDistance(queryCompact, token)
                        1.0 - (d.toDouble() / max(queryCompact.length, token.length))
                    }
                } ?: 0.0

                val bestFuzzy = maxOf(fullSim, phoneticSim, bestTokenSim)
                val minThreshold = when {
                    queryCompact.length <= 4 -> 0.74
                    queryCompact.length <= 6 -> 0.68
                    else -> 0.64
                }
                if (bestFuzzy >= minThreshold) {
                    score = 0.60 + (bestFuzzy * 0.25)
                }
            }

            if (score > 0.0) {
                results.add(app to score)
            }
        }

        return results.sortedWith(
            compareByDescending<Pair<LaunchableApp, Double>> { it.second }
                .thenBy { it.first.isSystemApp } // Prefer user-installed app on exact tie
                .thenBy { it.first.appName.length }
        )
    }

    private fun launchPackageOrComponent(
        packageName: String,
        activityName: String?,
        displayLabel: String
    ): AppLaunchOutcome? {
        val launchIntent: Intent = try {
            packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException getting launch intent for $packageName", e)
            return AppLaunchOutcome.LaunchFailed(
                appName = displayLabel,
                packageName = packageName,
                message = "Android prevented opening $displayLabel due to permission restrictions."
            )
        } catch (_: Exception) {
            null
        } ?: if (!activityName.isNullOrBlank()) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, activityName)
            }
        } else {
            val pkgMain = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val match = queryIntentActivitiesCompat(pkgMain).firstOrNull()
            match?.activityInfo?.let { info ->
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(info.packageName, info.name)
                }
            }
        } ?: return null

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val canResolve = try {
            launchIntent.resolveActivity(packageManager) != null || queryIntentActivitiesCompat(launchIntent).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (!canResolve) return null

        return try {
            appContext.startActivity(launchIntent)
            AppLaunchOutcome.Launched(
                appName = displayLabel,
                packageName = packageName,
                message = "Opening $displayLabel."
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting $packageName", e)
            AppLaunchOutcome.LaunchFailed(
                appName = displayLabel,
                packageName = packageName,
                message = "I don't have permission to launch $displayLabel directly on this Android version."
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ActivityNotFoundException starting $packageName; refreshing index", e)
            refreshIndex()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start $packageName", e)
            AppLaunchOutcome.LaunchFailed(
                appName = displayLabel,
                packageName = packageName,
                message = "Could not open $displayLabel right now."
            )
        }
    }

    private fun openCameraFallback(): AppLaunchOutcome {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryStartActivity(intent)) {
            AppLaunchOutcome.Launched("Camera", "android.media.action.STILL_IMAGE_CAMERA", "Opening Camera.")
        } else {
            AppLaunchOutcome.NotFound("Camera", "Camera app could not be opened on this device.")
        }
    }

    private fun openGalleryFallback(): AppLaunchOutcome {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryStartActivity(intent)) {
            AppLaunchOutcome.Launched("Gallery", "android.intent.action.VIEW", "Opening Gallery.")
        } else {
            AppLaunchOutcome.NotFound("Gallery", "Gallery app could not be found on this device.")
        }
    }

    private fun openCalculatorFallback(): AppLaunchOutcome {
        val calcIntent = try {
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) {
            null
        }
        if (calcIntent != null && tryStartActivity(calcIntent)) {
            return AppLaunchOutcome.Launched("Calculator", "android.intent.category.APP_CALCULATOR", "Opening Calculator.")
        }
        val calcPackages = listOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.oneplus.calculator",
            "com.coloros.calculator",
            "com.android.bbkcalculator"
        )
        for (pkg in calcPackages) {
            launchPackageOrComponent(pkg, null, "Calculator")?.let {
                if (it is AppLaunchOutcome.Launched) return it
            }
        }
        return AppLaunchOutcome.NotFound("Calculator", "Calculator app could not be found on your device.")
    }

    private fun openClockFallback(): AppLaunchOutcome {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (tryStartActivity(intent)) {
            AppLaunchOutcome.Launched("Clock", "android.provider.AlarmClock", "Opening Clock and Alarms.")
        } else {
            AppLaunchOutcome.NotFound("Clock", "Clock application not found on this device.")
        }
    }

    fun tryStartActivity(intent: Intent): Boolean {
        val canResolve = try {
            intent.resolveActivity(packageManager) != null || queryIntentActivitiesCompat(intent).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (!canResolve) return false
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity: ${intent.action ?: intent.component}", e)
            false
        }
    }

    fun queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivitiesCompat failed", e)
            emptyList()
        }
    }

    private fun normalizeLabel(label: String): String {
        return label.lowercase()
            .replace(Regex("""[^a-z0-9\u0900-\u097F\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Generates a coarse phonetic key so pronunciation variants & speech-to-text typos
     * (e.g. "crome" vs "chrome", "kainva" vs "canva", "fotos" vs "photos") collapse cleanly.
     */
    internal fun buildPhoneticKey(input: String): String {
        val clean = input.lowercase().replace(Regex("""[^a-z0-9]"""), "")
        if (clean.length < 2) return clean
        val replaced = clean
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("ch", "c")
            .replace("sh", "s")
            .replace("th", "t")
            .replace("wh", "v")
            .replace('w', 'v')
            .replace('q', 'k')
            .replace('z', 'j')
            .replace("ce", "se")
            .replace("ci", "si")
            .replace('c', 'k')
            .replace("oo", "u")
            .replace("ee", "i")
            .replace("ea", "i")
            .replace("ai", "e")
            .replace('y', 'i')
        val sb = StringBuilder()
        var prev = '\u0000'
        for ((index, ch) in replaced.withIndex()) {
            if (ch == prev) continue
            // Keep first character and consonants, drop internal weak vowels for skeleton stability
            if (index == 0 || ch !in charArrayOf('a', 'e', 'i', 'o', 'u', 'h')) {
                sb.append(ch)
            }
            prev = ch
        }
        return sb.toString()
    }

    /**
     * Dynamic Devanagari-to-Latin phonetic transliterator so any Hindi-spoken app name
     * (even newly installed apps not in a fixed dictionary) can match Latin app labels.
     */
    internal fun transliterateDevanagari(input: String): String = devanagariToLatin(input)

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }

    private fun formatCandidateList(names: List<String>): String {
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
        }
    }

    private fun displayNameFor(normalized: String, original: String): String {
        return when (normalized) {
            "whatsapp" -> "WhatsApp"
            "instagram" -> "Instagram"
            "facebook" -> "Facebook"
            "youtube" -> "YouTube"
            "chrome" -> "Chrome"
            "gmail" -> "Gmail"
            "play store", "playstore" -> "Play Store"
            else -> original.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    companion object {
        private const val TAG = "UniversalAppLauncher"
        private const val CACHE_TTL_MS = 20_000L

        fun devanagariToLatin(input: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                val next = input.getOrNull(i + 1)

                if (next == '\u093C') {
                    val nukta = when (ch) {
                        'क' -> "q"
                        'ख' -> "kh"
                        'ग' -> "gh"
                        'ज' -> "z"
                        'ड' -> "r"
                        'ढ' -> "rh"
                        'फ' -> "f"
                        else -> null
                    }
                    if (nukta != null) {
                        sb.append(nukta)
                        i += 2
                        val after = input.getOrNull(i)
                        if (after != null && after !in DEVANAGARI_MATRAS && after != '\u094D' && after in DEVANAGARI_CONSONANTS) {
                            sb.append('a')
                        }
                        continue
                    }
                }

                when {
                    ch in DEVANAGARI_CONSONANTS -> {
                        sb.append(DEVANAGARI_CONSONANTS[ch])
                        if (next == '\u094D') {
                            i += 2
                            continue
                        } else if (next != null && next in DEVANAGARI_MATRAS) {
                            sb.append(DEVANAGARI_MATRAS[next])
                            i += 2
                            continue
                        } else if (next != null && next != ' ' && next in DEVANAGARI_CONSONANTS) {
                            sb.append('a')
                        }
                    }
                    ch in DEVANAGARI_INDEPENDENT_VOWELS -> sb.append(DEVANAGARI_INDEPENDENT_VOWELS[ch])
                    ch in DEVANAGARI_MATRAS -> sb.append(DEVANAGARI_MATRAS[ch])
                    ch == '\u0902' || ch == '\u0901' -> sb.append('n')
                    ch == '\u094D' -> Unit
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }

        private val DEVANAGARI_INDEPENDENT_VOWELS = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee",
            'उ' to "u", 'ऊ' to "oo", 'ए' to "e", 'ऐ' to "ai",
            'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "ae"
        )

        private val DEVANAGARI_MATRAS = mapOf(
            '\u093E' to "a", '\u093F' to "i", '\u0940' to "ee",
            '\u0941' to "u", '\u0942' to "oo", '\u0947' to "e",
            '\u0948' to "ai", '\u094B' to "o", '\u094C' to "au",
            '\u0949' to "o", '\u0945' to "ae", '\u0943' to "ri"
        )

        private val DEVANAGARI_CONSONANTS = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
            'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h"
        )

        private val hindiAndPhoneticAliases = mapOf(
            // Hindi Devanagari
            "व्हाट्सएप" to "whatsapp",
            "व्हाट्सऐप" to "whatsapp",
            "वॉट्सएप" to "whatsapp",
            "वॉट्सऐप" to "whatsapp",
            "वाट्सएप" to "whatsapp",
            "व्हाट्स अप" to "whatsapp",
            "इंस्टाग्राम" to "instagram",
            "इन्सटाग्राम" to "instagram",
            "इंस्टा" to "instagram",
            "फेसबुक" to "facebook",
            "फेस बुक" to "facebook",
            "मैसेंजर" to "messenger",
            "यूट्यूब" to "youtube",
            "यू ट्यूब" to "youtube",
            "युट्युब" to "youtube",
            "यूट्यूब म्यूजिक" to "youtube music",
            "क्रोम" to "chrome",
            "गूगल क्रोम" to "chrome",
            "गूगल" to "google",
            "कैमरा" to "camera",
            "सेटिंग्स" to "settings",
            "सेटिंग" to "settings",
            "सैटिंग" to "settings",
            "गैलरी" to "gallery",
            "फोटो" to "photos",
            "फोटोज" to "photos",
            "फोटोज़" to "photos",
            "तस्वीरें" to "gallery",
            "कैलकुलेटर" to "calculator",
            "कैलक्युलेटर" to "calculator",
            "गणक" to "calculator",
            "घड़ी" to "clock",
            "अलार्म" to "clock",
            "क्लॉक" to "clock",
            "मैप्स" to "maps",
            "गूगल मैप्स" to "maps",
            "नक्शा" to "maps",
            "स्पॉटीफाई" to "spotify",
            "स्पॉटिफाई" to "spotify",
            "टेलीग्राम" to "telegram",
            "स्नैपचैट" to "snapchat",
            "ट्विटर" to "twitter",
            "जीमेल" to "gmail",
            "प्ले स्टोर" to "play store",
            "प्लेस्टोर" to "play store",
            "फोन" to "phone",
            "डायलर" to "phone",
            "मैसेज" to "messages",
            "मैसेजेस" to "messages",
            "कॉन्टैक्ट्स" to "contacts",
            "कैलेंडर" to "calendar",
            "फाइल्स" to "files",
            "फाइल मैनेजर" to "files",
            "अमेज़न" to "amazon",
            "फ्लिपकार्ट" to "flipkart",
            "पेटीएम" to "paytm",
            "फोनपे" to "phonepe",
            "गूगल पे" to "google pay",
            "जी पे" to "google pay",
            "नेटफ्लिक्स" to "netflix",
            "लिंक्डइन" to "linkedin",
            "ज़ोमैटो" to "zomato",
            "जोमैटो" to "zomato",
            "स्विगी" to "swiggy",
            "उबर" to "uber",
            "ओला" to "ola",
            "ट्रूकॉलर" to "truecaller",
            // Common STT / pronunciation / spelling variations
            "utube" to "youtube",
            "u tube" to "youtube",
            "you tube" to "youtube",
            "yutube" to "youtube",
            "watsapp" to "whatsapp",
            "watsap" to "whatsapp",
            "whatsap" to "whatsapp",
            "whats app" to "whatsapp",
            "what's app" to "whatsapp",
            "instgram" to "instagram",
            "instagrm" to "instagram",
            "insta" to "instagram",
            "fbook" to "facebook",
            "face book" to "facebook",
            "crome" to "chrome",
            "krome" to "chrome",
            "kroam" to "chrome",
            "calci" to "calculator",
            "calc" to "calculator",
            "calculater" to "calculator",
            "galery" to "gallery",
            "gallary" to "gallery",
            "gallry" to "gallery",
            "setings" to "settings",
            "seting" to "settings",
            "spotifi" to "spotify",
            "spotfy" to "spotify",
            "teligram" to "telegram",
            "snachat" to "snapchat",
            "snap chat" to "snapchat",
            "twiter" to "twitter",
            "g mail" to "gmail",
            "g pay" to "google pay",
            "phone pay" to "phonepe",
            "pay tm" to "paytm",
            "net flix" to "netflix"
        )

        private val aliasPackageCandidates: Map<String, List<String>> = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whatsapp business" to listOf("com.whatsapp.w4b", "com.whatsapp"),
            "instagram" to listOf("com.instagram.android", "com.instagram.lite"),
            "insta" to listOf("com.instagram.android", "com.instagram.lite"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "fb" to listOf("com.facebook.katana", "com.facebook.lite"),
            "facebook lite" to listOf("com.facebook.lite", "com.facebook.katana"),
            "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "youtube" to listOf("com.google.android.youtube", "com.google.android.apps.youtube.music"),
            "yt" to listOf("com.google.android.youtube"),
            "youtube music" to listOf("com.google.android.apps.youtube.music"),
            "chrome" to listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev"),
            "google chrome" to listOf("com.android.chrome", "com.chrome.beta"),
            "browser" to listOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.microsoft.emmx"),
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "spotify" to listOf("com.spotify.music", "com.spotify.lite"),
            "telegram" to listOf("org.telegram.messenger", "org.telegram.plus"),
            "snapchat" to listOf("com.snapchat.android"),
            "twitter" to listOf("com.twitter.android", "com.twitter.android.lite"),
            "x" to listOf("com.twitter.android"),
            "gmail" to listOf("com.google.android.gm"),
            "mail" to listOf("com.google.android.gm"),
            "email" to listOf("com.google.android.gm"),
            "play store" to listOf("com.android.vending"),
            "playstore" to listOf("com.android.vending"),
            "photos" to listOf("com.google.android.apps.photos"),
            "google photos" to listOf("com.google.android.apps.photos"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "dialer" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
            "sms" to listOf("com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging"),
            "contacts" to listOf("com.google.android.contacts", "com.android.contacts", "com.samsung.android.contacts"),
            "calendar" to listOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar"),
            "files" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
            "file manager" to listOf("com.google.android.apps.nbu.files", "com.android.documentsui"),
            "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
            "flipkart" to listOf("com.flipkart.android"),
            "paytm" to listOf("net.one97.paytm"),
            "phonepe" to listOf("com.phonepe.app"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "netflix" to listOf("com.netflix.mediaclient"),
            "linkedin" to listOf("com.linkedin.android")
        )
    }
}
