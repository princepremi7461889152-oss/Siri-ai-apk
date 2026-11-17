package com.example.device

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.example.model.WebSourceLink
import com.example.service.SiriNotificationListenerService

class AndroidDeviceController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    val contactAssistant = ContactAssistant(context)
    val reminderScheduler = ReminderScheduler(context)

    private var isTorchOn: Boolean = false

    /**
     * Toggles or sets the device flashlight/torch
     */
    fun setFlashlight(enable: Boolean): Pair<Boolean, String> {
        val manager = cameraManager ?: return Pair(false, "Camera hardware is not available on this device.")
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) {
                Pair(false, "No camera with flashlight found on this device.")
            } else {
                manager.setTorchMode(cameraId, enable)
                isTorchOn = enable
                val status = if (enable) "turned on" else "turned off"
                Pair(true, "Flashlight has been $status.")
            }
        } catch (e: Exception) {
            Log.e("SiriDevice", "Flashlight error", e)
            Pair(false, "Could not control flashlight: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    fun isFlashlightActive(): Boolean = isTorchOn

    val appLauncher = UniversalAppLauncher(context)

    fun release() {
        appLauncher.unregister()
    }

    fun openUrl(url: String): Pair<Boolean, String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening link.")
        } catch (e: Exception) {
            Pair(false, "Browser is unavailable to open that link.")
        }
    }

    /**
     * Normalizes an app name or Hindi/Hinglish spoken target to a clean lookup key.
     */
    fun normalizeAppTarget(rawTarget: String): String = appLauncher.normalizeAppTarget(rawTarget)

    /**
     * Launches an installed Android app with full Universal App Launcher outcome details
     * (Launched, DisambiguationNeeded, NotFound, or LaunchFailed).
     */
    fun openAppWithResult(target: String): AppLaunchOutcome {
        val trimmed = target.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val (ok, msg) = openUrl(trimmed)
            return if (ok) {
                AppLaunchOutcome.Launched("Browser", trimmed, msg)
            } else {
                AppLaunchOutcome.LaunchFailed("Browser", trimmed, msg)
            }
        }
        return appLauncher.openApp(trimmed)
    }

    /**
     * Launches a specific chosen application (e.g., after disambiguation).
     */
    fun launchSpecificApp(packageName: String, activityName: String?, appName: String): AppLaunchOutcome {
        return appLauncher.launchSpecificApp(packageName, activityName, appName)
    }

    /**
     * Backward-compatible Pair<Boolean, String> launcher wrapper.
     */
    fun openApp(target: String): Pair<Boolean, String> {
        return when (val outcome = openAppWithResult(target)) {
            is AppLaunchOutcome.Launched -> Pair(true, outcome.message)
            is AppLaunchOutcome.DisambiguationNeeded -> Pair(true, outcome.message)
            is AppLaunchOutcome.NotFound -> Pair(false, outcome.message)
            is AppLaunchOutcome.LaunchFailed -> Pair(false, outcome.message)
        }
    }

    fun openCameraApp(): Pair<Boolean, String> {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Camera.")
        } catch (e: Exception) {
            Pair(false, "Camera app could not be opened.")
        }
    }

    fun openGalleryApp(): Pair<Boolean, String> {
        val outcome = appLauncher.openApp("gallery")
        return when (outcome) {
            is AppLaunchOutcome.Launched -> Pair(true, outcome.message)
            is AppLaunchOutcome.DisambiguationNeeded -> Pair(true, outcome.message)
            else -> Pair(false, "Gallery could not be opened.")
        }
    }

    fun openCalculatorApp(): Pair<Boolean, String> {
        val outcome = appLauncher.openApp("calculator")
        return when (outcome) {
            is AppLaunchOutcome.Launched -> Pair(true, outcome.message)
            is AppLaunchOutcome.DisambiguationNeeded -> Pair(true, outcome.message)
            else -> Pair(false, "Calculator could not be opened.")
        }
    }

    fun openClockApp(): Pair<Boolean, String> {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Clock and Alarms.")
        } catch (e: Exception) {
            Pair(false, "Clock application not found.")
        }
    }

    fun openSettings(action: String = Settings.ACTION_SETTINGS, label: String = "Settings"): Pair<Boolean, String> {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening $label.")
        } catch (e: Exception) {
            Pair(false, "Could not open $label on this device.")
        }
    }

    /**
     * Sets a countdown timer via standard Android Clock
     */
    fun setTimer(durationSeconds: Int, message: String = "Siri Timer"): Pair<Boolean, String> {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            val timeStr = if (minutes > 0) "$minutes minutes${if (seconds > 0) " $seconds seconds" else ""}" else "$seconds seconds"
            Pair(true, "Timer set for $timeStr.")
        } catch (e: Exception) {
            Pair(false, "Could not set timer: no clock app found.")
        }
    }

    /**
     * Sets an alarm for specified hour (0-23) and minute
     */
    fun setAlarm(hour: Int, minute: Int, message: String = "Siri Alarm"): Pair<Boolean, String> {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val amPm = if (hour >= 12) "PM" else "AM"
            val displayHour = if (hour % 12 == 0) 12 else hour % 12
            val minStr = String.format("%02d", minute)
            Pair(true, "Alarm set for $displayHour:$minStr $amPm.")
        } catch (e: Exception) {
            Pair(false, "Could not set alarm: no clock app available.")
        }
    }

    /**
     * Queries battery percentage and charging state
     */
    fun getBatteryInfo(): String {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return if (isCharging) {
            "Your battery is at $pct% and is currently charging."
        } else {
            "Your battery is at $pct%."
        }
    }

    /**
     * Checks whether the device currently has active internet connectivity.
     */
    fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Registers a live ConnectivityManager NetworkCallback and returns an unregister cleanup lambda.
     */
    fun observeConnectivity(onChanged: (Boolean) -> Unit): () -> Unit {
        val cm = connectivityManager ?: return {}
        onChanged(isNetworkAvailable())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChanged(true)
            }

            override fun onLost(network: Network) {
                onChanged(isNetworkAvailable())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val connected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                onChanged(connected)
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            val cleanup: () -> Unit = {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
            }
            return cleanup
        } catch (e: Exception) {
            Log.w("SiriDevice", "Could not register live NetworkCallback", e)
            return {}
        }
    }

    /**
     * Queries connectivity state
     */
    fun getNetworkInfo(): String {
        val cm = connectivityManager ?: return "Network status is unavailable."
        val activeNetwork = cm.activeNetwork ?: return "You are currently offline."
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "You are currently offline."

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected to Wi-Fi with active internet."
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected via mobile data."
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connected via ethernet."
            else -> "Connected to network."
        }
    }

    /**
     * Queries internal device storage space cleanly via StatFs.
     */
    fun getStorageInfo(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes
            val availableBytes = stat.availableBytes
            val usedBytes = (totalBytes - availableBytes).coerceAtLeast(0L)
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val availGb = availableBytes / (1024.0 * 1024.0 * 1024.0)
            val usedPct = if (totalBytes > 0L) ((usedBytes * 100L) / totalBytes).toInt() else 0
            String.format(
                java.util.Locale.US,
                "Device storage is %d%% used, with %.1f GB free out of %.1f GB total.",
                usedPct,
                availGb,
                totalGb
            )
        } catch (e: Exception) {
            "Storage information could not be read on this device."
        }
    }

    /**
     * Returns actual hardware & OS information for Offline Mode / Device Info queries.
     */
    fun getDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val osVersion = Build.VERSION.RELEASE.orEmpty()
        val sdkInt = Build.VERSION.SDK_INT
        val ramSummary = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0L) {
                val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
                val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
                String.format(java.util.Locale.US, " RAM: %.1f GB free of %.1f GB.", availGb, totalGb)
            } else ""
        } catch (_: Exception) {
            ""
        }
        return "Device: $manufacturer $model, Android $osVersion (API $sdkInt).$ramSummary ${getStorageInfo()}"
    }

    /**
     * Honest hardware check (Android 14+ & >= 6GB RAM) for reporting device readiness for on-device engines.
     */
    fun isOnDeviceAiHardwareSupported(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                memInfo.totalMem >= 6L * 1024L * 1024L * 1024L
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Adjusts media volume up, down, or mute
     */
    fun adjustVolume(direction: String): Pair<Boolean, String> {
        val am = audioManager ?: return Pair(false, "Audio manager is unavailable.")
        return try {
            when (direction.lowercase()) {
                "up", "increase" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    Pair(true, "Volume raised.")
                }
                "down", "lower", "decrease" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    Pair(true, "Volume lowered.")
                }
                "mute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    Pair(true, "Media audio muted.")
                }
                "unmute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    Pair(true, "Media audio unmuted.")
                }
                else -> Pair(false, "Unknown volume action.")
            }
        } catch (e: Exception) {
            Pair(false, "Unable to adjust volume.")
        }
    }

    /**
     * Performs a web search with the query (offline-aware) and returns official/search links.
     */
    fun searchWeb(query: String): Pair<Boolean, String> {
        if (!isNetworkAvailable()) {
            return Pair(
                false,
                "You appear to be offline right now, so I can't run a live web search for \"$query\". Please check Wi-Fi or mobile data and try again."
            )
        }
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Searching the web for $query.")
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
                Pair(true, "Searching Google for $query.")
            } catch (_: Exception) {
                Pair(false, "No browser is available to open web search results.")
            }
        }
    }

    /**
     * Resolves relevant official website links alongside grounded search links for a query.
     */
    fun resolveOfficialWebsiteLinks(query: String): List<WebSourceLink> {
        val lower = query.lowercase().trim()
        val encoded = Uri.encode(query)
        val links = mutableListOf<WebSourceLink>()

        val officialDirectory = mapOf(
            "youtube" to ("YouTube Official" to "https://www.youtube.com"),
            "google" to ("Google Official" to "https://www.google.com"),
            "wikipedia" to ("Wikipedia Official" to "https://www.wikipedia.org"),
            "github" to ("GitHub Official" to "https://github.com"),
            "android" to ("Android Developers Official" to "https://developer.android.com"),
            "nasa" to ("NASA Official" to "https://www.nasa.gov"),
            "who" to ("World Health Organization" to "https://www.who.int"),
            "apple" to ("Apple Official" to "https://www.apple.com"),
            "microsoft" to ("Microsoft Official" to "https://www.microsoft.com"),
            "amazon" to ("Amazon Official" to "https://www.amazon.com"),
            "flipkart" to ("Flipkart Official" to "https://www.flipkart.com"),
            "irctc" to ("IRCTC Official" to "https://www.irctc.co.in"),
            "uidai" to ("UIDAI Aadhaar Official" to "https://uidai.gov.in"),
            "aadhaar" to ("UIDAI Aadhaar Official" to "https://uidai.gov.in"),
            "upi" to ("NPCI UPI Official" to "https://www.npci.org.in"),
            "bbc" to ("BBC News Official" to "https://www.bbc.com"),
            "openai" to ("OpenAI Official" to "https://openai.com"),
            "gemini" to ("Google Gemini Official" to "https://gemini.google.com"),
            "maps" to ("Google Maps Official" to "https://maps.google.com")
        )

        for ((keyword, pair) in officialDirectory) {
            if (lower.contains(keyword)) {
                links.add(WebSourceLink(pair.first, pair.second, "Official website"))
            }
        }

        // If user asked for a domain directly (e.g. "open nasa.gov")
        val domainMatch = Regex("""([a-z0-9-]+\.(?:com|org|in|gov|edu|io|net|co\.in))""").find(lower)
        if (domainMatch != null) {
            val domain = domainMatch.groupValues[1]
            val url = "https://$domain"
            if (links.none { it.url.equals(url, ignoreCase = true) }) {
                links.add(0, WebSourceLink("Official Site ($domain)", url, "Direct website link"))
            }
        }

        links.add(WebSourceLink("Google Search: $query", "https://www.google.com/search?q=$encoded", "Live web search results"))
        links.add(WebSourceLink("Wikipedia: $query", "https://en.wikipedia.org/wiki/Special:Search?search=$encoded", "Encyclopedia reference"))
        return links.distinctBy { it.url }
    }

    /**
     * Shares text through Android's standard share sheet
     */
    fun shareContent(text: String, title: String = "Share via Siri"): Pair<Boolean, String> {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(shareIntent)
            Pair(true, "Opening sharing options.")
        } catch (e: Exception) {
            Pair(false, "Could not open share sheet.")
        }
    }

    /**
     * Opens the dialer with a phone number for user-confirmed calling
     */
    fun dialPhoneNumber(phoneNumber: String): Pair<Boolean, String> {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$cleanNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening phone dialer for $phoneNumber.")
        } catch (e: Exception) {
            Pair(false, "Phone dialer could not be opened.")
        }
    }

    /**
     * Opens the SMS composer with phone number and message
     */
    fun composeSms(phoneNumber: String, messageText: String): Pair<Boolean, String> {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val uri = if (cleanNumber.isNotBlank()) Uri.parse("smsto:$cleanNumber") else Uri.parse("smsto:")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening SMS composer with prepared message.")
        } catch (e: Exception) {
            Pair(false, "SMS application not available.")
        }
    }

    /**
     * Opens Google Maps (or general map app)
     */
    fun openGoogleMaps(): Pair<Boolean, String> {
        val mapsOutcome = appLauncher.openApp("google maps")
        if (mapsOutcome is AppLaunchOutcome.Launched) {
            return Pair(true, "Opening Google Maps.")
        }
        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(mapIntent)
            Pair(true, "Opening Maps.")
        } catch (e: Exception) {
            Pair(false, "Maps application is not available on this device.")
        }
    }

    /**
     * Searches places on Google Maps with offline handling.
     */
    fun searchPlacesOnMaps(placeQuery: String): Pair<Boolean, String> {
        if (!isNetworkAvailable()) {
            return Pair(
                false,
                "You are currently offline, so I can't search live places for \"$placeQuery\". Turn on internet to search Google Maps."
            )
        }
        val encoded = Uri.encode(placeQuery)
        val mapsSearchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(mapsSearchIntent)
            Pair(true, "Searching Google Maps for $placeQuery.")
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
                Pair(true, "Searching maps for $placeQuery.")
            } catch (_: Exception) {
                openUrl("https://www.google.com/maps/search/?api=1&query=$encoded")
            }
        }
    }

    /**
     * Opens turn-by-turn map navigation to a destination with offline handling.
     */
    fun openNavigation(destination: String): Pair<Boolean, String> {
        if (!isNetworkAvailable()) {
            return Pair(
                false,
                "You appear to be offline, so live turn-by-turn navigation to $destination requires an active connection or downloaded offline maps."
            )
        }
        val encodedDest = Uri.encode(destination)
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encodedDest")).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(navIntent)
            Pair(true, "Starting navigation to $destination.")
        } catch (e: Exception) {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedDest")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(geoIntent)
                Pair(true, "Opening maps for $destination.")
            } catch (ex: Exception) {
                Pair(false, "Maps application not found.")
            }
        }
    }

    /**
     * Creates a calendar event via standard calendar insert intent
     */
    fun createCalendarEvent(title: String, description: String = "", beginTimeMillis: Long = System.currentTimeMillis() + 3600000L): Pair<Boolean, String> {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = android.provider.CalendarContract.Events.CONTENT_URI
            putExtra(android.provider.CalendarContract.Events.TITLE, title)
            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Calendar to add event '$title'.")
        } catch (e: Exception) {
            Pair(false, "Calendar application not found.")
        }
    }

    /**
     * Copies text to system clipboard
     */
    fun copyToClipboard(label: String, text: String): Boolean {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Controls music playback using Android MediaSessionManager active sessions first
     * (when Notification Listener permission is active), falling back gracefully to AudioManager media keys.
     */
    fun controlMediaPlayback(action: String): Pair<Boolean, String> {
        val upper = action.uppercase()
        try {
            val sessions: List<MediaController> = try {
                mediaSessionManager?.getActiveSessions(SiriNotificationListenerService.componentName(context))
                    ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val controller = sessions.firstOrNull()
            if (controller != null) {
                val trackTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                val trackArtist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                val trackDesc = when {
                    !trackTitle.isNullOrBlank() && !trackArtist.isNullOrBlank() -> " ($trackTitle by $trackArtist)"
                    !trackTitle.isNullOrBlank() -> " ($trackTitle)"
                    else -> ""
                }
                val controls = controller.transportControls
                when (upper) {
                    "PLAY" -> {
                        controls.play()
                        return Pair(true, "Resuming playback$trackDesc.")
                    }
                    "PAUSE" -> {
                        controls.pause()
                        return Pair(true, "Paused media playback$trackDesc.")
                    }
                    "NEXT" -> {
                        controls.skipToNext()
                        return Pair(true, "Skipping to next track.")
                    }
                    "PREVIOUS" -> {
                        controls.skipToPrevious()
                        return Pair(true, "Playing previous track.")
                    }
                    "NOW_PLAYING" -> {
                        return if (trackDesc.isNotBlank()) {
                            Pair(true, "Currently playing${trackDesc}.")
                        } else {
                            Pair(true, "A media session from ${controller.packageName} is active.")
                        }
                    }
                }
            } else if (upper == "NOW_PLAYING") {
                val musicActive = audioManager?.isMusicActive == true
                return if (musicActive) {
                    Pair(true, "Audio playback is currently active. Enable Notification Listener in Privacy settings for track details.")
                } else {
                    Pair(false, "No music or media session is playing right now.")
                }
            }
        } catch (e: Exception) {
            Log.w("SiriDevice", "MediaSession control error; falling back to key events", e)
        }

        val keyCode = when (upper) {
            "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val (ok, _) = sendMediaKeyEvent(keyCode)
        val msg = when (upper) {
            "PLAY" -> "Resuming playback."
            "PAUSE" -> "Paused media playback."
            "NEXT" -> "Skipping to next track."
            "PREVIOUS" -> "Playing previous track."
            else -> "Media playback controlled."
        }
        return Pair(ok, msg)
    }

    /**
     * Dispatches media key event (Play/Pause, Next, Previous)
     */
    fun sendMediaKeyEvent(keyCode: Int): Pair<Boolean, String> {
        return try {
            val am = audioManager ?: return Pair(false, "Audio manager unavailable")
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            Pair(true, "Media playback controlled.")
        } catch (e: Exception) {
            Pair(false, "Could not control media session.")
        }
    }

    fun openAccessibilitySettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility Settings")

    fun openNotificationSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Notification Settings")

    fun openWifiSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")

    fun openBluetoothSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")

    fun openDisplaySettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings")

    fun openSoundSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_SOUND_SETTINGS, "Sound Settings")

    fun openLocationSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location Settings")

    fun openBatterySettings(): Pair<Boolean, String> =
        openSettings(Intent.ACTION_POWER_USAGE_SUMMARY, "Battery Settings")

    fun openStorageSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage Settings")

    fun openHotspotSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_WIRELESS_SETTINGS, "Network & Hotspot Settings")

    fun openDateSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_DATE_SETTINGS, "Date & Time Settings")

    fun openAppsSettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, "App Management Settings")

    fun openPrivacySettings(): Pair<Boolean, String> =
        openSettings(Settings.ACTION_PRIVACY_SETTINGS, "Privacy Settings")
}
