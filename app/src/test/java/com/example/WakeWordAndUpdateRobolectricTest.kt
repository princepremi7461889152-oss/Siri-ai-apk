package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.ApkInstallLaunchOutcome
import com.example.data.AppUpdateManager
import com.example.data.SiriRepository
import com.example.data.UpdateCheckOutcome
import com.example.model.AiConfig
import com.example.model.AppUpdateState
import com.example.model.LanguageOption
import com.example.model.UpdateInfo
import com.example.model.VoiceGender
import com.example.model.VoicePersonality
import com.example.model.WakeWordOption
import com.example.service.SiriVoiceService
import com.example.viewmodel.SiriViewModel
import com.example.voice.AlwaysOnWakeWordEngine
import com.example.voice.OfflineHotwordAcousticClassifier
import com.example.voice.SoundTriggerCapabilityProbe
import com.example.voice.WakeEngineBackend
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeWordAndUpdateRobolectricTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        setNetworkAvailable(true)
        SiriVoiceService.isServiceRunning = false
        SiriVoiceService.isWakeStandbyActive = false
    }

    @After
    fun tearDown() {
        SiriVoiceService.isServiceRunning = false
        SiriVoiceService.isWakeStandbyActive = false
    }

    private fun setNetworkAvailable(online: Boolean) {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val shadowCm = shadowOf(cm)
        if (online) {
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowCm.setNetworkCapabilities(cm.activeNetwork, caps)
        } else {
            shadowCm.setNetworkCapabilities(cm.activeNetwork, null)
        }
    }

    private fun appendSilence(out: MutableList<Short>, durationMs: Int) {
        val count = (OfflineHotwordAcousticClassifier.SAMPLE_RATE * durationMs) / 1000
        repeat(count) { out.add(0) }
    }

    private fun appendVoicedFormants(out: MutableList<Short>, durationMs: Int, amplitude: Float = 0.42f) {
        val sr = OfflineHotwordAcousticClassifier.SAMPLE_RATE
        val count = (sr * durationMs) / 1000
        for (i in 0 until count) {
            val env = 0.7f + 0.3f * sin(2.0 * PI * 6.0 * i / sr).toFloat()
            val sample = (sin(2.0 * PI * 650.0 * i / sr) * 0.55 + sin(2.0 * PI * 1650.0 * i / sr) * 0.45) * amplitude * env
            out.add((sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun appendSibilant(out: MutableList<Short>, durationMs: Int, amplitude: Float = 0.28f) {
        val sr = OfflineHotwordAcousticClassifier.SAMPLE_RATE
        val count = (sr * durationMs) / 1000
        for (i in 0 until count) {
            val sample = sin(2.0 * PI * 4600.0 * i / sr) * amplitude
            out.add((sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun buildHeySiriWaveform(): ShortArray {
        val samples = mutableListOf<Short>()
        appendSilence(samples, 60)
        appendVoicedFormants(samples, 180) // "Hey"
        appendSilence(samples, 20)
        appendSibilant(samples, 120)       // "s"
        appendVoicedFormants(samples, 240) // "iri"
        appendSilence(samples, 180)
        return samples.toShortArray()
    }

    private fun buildSiriWaveform(): ShortArray {
        val samples = mutableListOf<Short>()
        appendSilence(samples, 60)
        appendSibilant(samples, 120)       // "s"
        appendVoicedFormants(samples, 260) // "iri"
        appendSilence(samples, 180)
        return samples.toShortArray()
    }

    @Test
    fun testOfflineAcousticClassifierDetectsHeySiriAndSiriAndRejectsNoise() {
        val heySiriPcm = buildHeySiriWaveform()
        val resHey = OfflineHotwordAcousticClassifier.evaluatePcmWindow(
            pcm = heySiriPcm,
            option = WakeWordOption.HEY_SIRI
        )
        assertTrue("Expected Hey Siri detection, got rejected: ${resHey.rejectionReason}", resHey.detected)
        assertEquals(WakeWordOption.HEY_SIRI.label, resHey.matchedPhrase)
        assertTrue(resHey.confidence >= 0.70f)

        val siriPcm = buildSiriWaveform()
        val resSiri = OfflineHotwordAcousticClassifier.evaluatePcmWindow(
            pcm = siriPcm,
            option = WakeWordOption.SIRI
        )
        assertTrue("Expected Siri detection, got rejected: ${resSiri.rejectionReason}", resSiri.detected)
        assertEquals(WakeWordOption.SIRI.label, resSiri.matchedPhrase)

        // Saying only "Siri" when strict HEY_SIRI mode is active should not false-trigger
        val siriRejectedInHeyMode = OfflineHotwordAcousticClassifier.evaluatePcmWindow(
            pcm = siriPcm,
            option = WakeWordOption.HEY_SIRI
        )
        assertFalse(siriRejectedInHeyMode.detected)

        // Flat broadband fan/static noise must be rejected
        val rng = Random(42)
        val flatNoise = ShortArray(OfflineHotwordAcousticClassifier.SAMPLE_RATE) {
            rng.nextInt(-3000, 3000).toShort()
        }
        val resNoise = OfflineHotwordAcousticClassifier.evaluatePcmWindow(
            pcm = flatNoise,
            option = WakeWordOption.SIRI
        )
        assertFalse(resNoise.detected)

        // Short click (<260ms) must be rejected
        val clickPcm = ShortArray(1600) { 8000 }
        assertFalse(
            OfflineHotwordAcousticClassifier.evaluatePcmWindow(
                pcm = clickPcm,
                option = WakeWordOption.HEY_SIRI
            ).detected
        )
    }

    @Test
    fun testSoundTriggerProbeAndContinuousEngineZeroPollingBehavior() {
        val status = SoundTriggerCapabilityProbe.probe(app)
        // Standard non-system apps do not possess privileged CAPTURE_AUDIO_HOTWORD
        assertFalse(status.captureHotwordPermissionGranted)
        assertEquals(WakeEngineBackend.CONTINUOUS_OFFLINE_AUDIO_RECORD, status.activeBackend)
        assertTrue(status.detailMessage.isNotBlank())

        val engine = AlwaysOnWakeWordEngine(
            context = app,
            onWakeDetected = { _, _ -> }
        )
        assertFalse(engine.isListening())
        assertEquals(0, engine.getMicOpenSessionCount())
        engine.stop()
    }

    @Test
    fun testBackgroundWakeStandbyPreservesScreenOffAndExistingAssistantSettings() {
        val repo = SiriRepository(app)
        val customConfig = AiConfig(
            provider = "Custom / OpenAI API",
            model = "gpt-4o-mini",
            customApiKey = "sk-test-123456",
            customEndpoint = "https://api.example.com/v1"
        )
        repo.updateAiConfig(customConfig)
        repo.updateVoiceGender(VoiceGender.MALE)
        repo.updateLanguage(LanguageOption.HINGLISH)
        repo.updatePersonality(VoicePersonality.BOLD)
        repo.updateWakeWord(WakeWordOption.SIRI)

        val controller = Robolectric.buildService(SiriVoiceService::class.java).create()
        val service = controller.get()

        service.onStartCommand(
            Intent(app, SiriVoiceService::class.java).apply {
                action = SiriVoiceService.ACTION_START_WAKE_STANDBY
                putExtra(SiriVoiceService.EXTRA_WAKE_STANDBY, true)
            },
            0,
            1
        )
        assertTrue(SiriVoiceService.isServiceRunning)
        assertTrue(SiriVoiceService.isWakeStandbyActive)

        // Screen off should NOT drop background wake word standby readiness
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(SiriVoiceService.isWakeStandbyActive)

        // Simulated wake trigger preserves user's selected AI model, voice gender, language, personality
        service.onOfflineWakeWordTriggered("Siri", 0.91f)
        shadowOf(Looper.getMainLooper()).idle()

        val reloadedRepo = SiriRepository(app)
        assertEquals("Custom / OpenAI API", reloadedRepo.aiConfig.value.provider)
        assertEquals("gpt-4o-mini", reloadedRepo.aiConfig.value.model)
        assertEquals("sk-test-123456", reloadedRepo.aiConfig.value.customApiKey)
        assertEquals(VoiceGender.MALE, reloadedRepo.voiceGender.value)
        assertEquals(LanguageOption.HINGLISH, reloadedRepo.selectedLanguage.value)
        assertEquals(VoicePersonality.BOLD, reloadedRepo.voicePersonality.value)
        assertEquals(WakeWordOption.SIRI, reloadedRepo.wakeWordOption.value)

        controller.destroy()
    }

    @Test
    fun testViewModelOfflineWakeTriggerStopsStandbyAndPreservesConfig() {
        val vm = SiriViewModel(app)
        vm.setVoiceGender(VoiceGender.MALE)
        vm.setLanguage(LanguageOption.HINDI)
        vm.setWakeWordOption(WakeWordOption.SIRI)

        vm.startWakeWordStandby()
        assertTrue(vm.isWakeWordStandby.value)

        vm.handleOfflineWakeTriggeredInUi("Siri", 0.89f)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.isWakeWordStandby.value)
        assertEquals(VoiceGender.MALE, vm.repository.voiceGender.value)
        assertEquals(LanguageOption.HINDI, vm.repository.selectedLanguage.value)
        assertEquals(WakeWordOption.SIRI, vm.repository.wakeWordOption.value)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createMockClient(
        releaseJson: String,
        apkBytes: ByteArray = ByteArray(0),
        checksumBody: String? = null
    ): OkHttpClient {
        val interceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            when {
                url.contains("/releases/latest") -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(releaseJson.toResponseBody("application/json".toMediaType()))
                    .build()
                url.endsWith(".apk") -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(apkBytes.toResponseBody("application/vnd.android.package-archive".toMediaType()))
                    .build()
                url.endsWith(".sha256") -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body((checksumBody ?: "").toResponseBody("text/plain".toMediaType()))
                    .build()
                else -> Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    @Test
    fun testGitHubReleaseUpdateCheckDownloadSha256VerificationAndLaterSuppression() = runBlocking {
        // Build fake APK bytes with valid ZIP magic ("PK") and >= 2048 bytes
        val validApkBytes = ByteArray(2048) { idx -> (idx % 251).toByte() }.apply {
            this[0] = 'P'.code.toByte()
            this[1] = 'K'.code.toByte()
            this[2] = 0x03
            this[3] = 0x04
        }
        val expectedSha = sha256Hex(validApkBytes)
        val newerCode = BuildConfig.VERSION_CODE + 10

        val releaseJson = """
            {
              "tag_name": "v9.5.0+$newerCode",
              "name": "Siri AI Assistant v9.5.0 (Build $newerCode)",
              "html_url": "https://github.com/princepremi7461889152-oss/Siri-ai-apk/releases/tag/v9.5.0",
              "body": "Version Code: $newerCode\nSHA-256: $expectedSha\nBug fixes and wake upgrades.",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "size": ${validApkBytes.size},
                  "browser_download_url": "https://example.test/releases/app-debug.apk"
                },
                {
                  "name": "app-debug.apk.sha256",
                  "size": 78,
                  "browser_download_url": "https://example.test/releases/app-debug.apk.sha256"
                }
              ]
            }
        """.trimIndent()

        val manager = AppUpdateManager(app, createMockClient(releaseJson, validApkBytes, "$expectedSha  app-debug.apk"))
        val checkOutcome = manager.checkForLatestRelease(
            repoSlug = "princepremi7461889152-oss/Siri-ai-apk",
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
            apiBaseUrl = "https://api.github.test"
        )
        assertTrue(checkOutcome is UpdateCheckOutcome.UpdateAvailable)
        val info = (checkOutcome as UpdateCheckOutcome.UpdateAvailable).updateInfo
        assertEquals(newerCode, info.latestVersionCode)
        assertEquals("9.5.0", info.latestVersionName)
        assertEquals(expectedSha, info.sha256Checksum)

        // Valid SHA-256 download succeeds
        val progressUpdates = mutableListOf<Float>()
        val downloadSuccess = manager.downloadAndVerifyApk(info) { progressUpdates.add(it) }
        assertTrue("Expected verified download success", downloadSuccess.isSuccess)
        val downloadedFile = downloadSuccess.getOrThrow()
        assertTrue(downloadedFile.exists())
        assertEquals(1f, progressUpdates.last(), 0.001f)

        // Corrupt SHA-256 checksum must fail verification & delete unverified download
        val tamperedInfo = info.copy(sha256Checksum = "0".repeat(64))
        val downloadTampered = manager.downloadAndVerifyApk(tamperedInfo) {}
        assertTrue("Tampered SHA-256 must fail", downloadTampered.isFailure)
        assertTrue(downloadTampered.exceptionOrNull() is SecurityException)

        // Official Android installer permission gating (never silent install)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        val permNeededOutcome = manager.launchOfficialPackageInstaller(downloadedFile)
        assertEquals(ApkInstallLaunchOutcome.UnknownSourcesPermissionNeeded, permNeededOutcome)
        val permIntent = shadowOf(app).nextStartedActivity
        assertNotNull(permIntent)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permIntent.action)

        // Preserve user preferences & verify "Later" suppresses repeated auto dialog for same version
        val vm = SiriViewModel(app)
        vm.repository.updateUserPreferences(vm.repository.userPreferences.value.copy(userName = "Prem"))
        vm.checkForUpdates(userInitiated = false, customManager = manager, apiBaseUrl = "https://api.github.test")
        shadowOf(Looper.getMainLooper()).idle()

        // Allow IO/ViewModel coroutines to settle
        var waitCycles = 0
        while (vm.appUpdateState.value !is AppUpdateState.UpdateAvailable && waitCycles < 30) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            waitCycles++
        }
        assertTrue(vm.appUpdateState.value is AppUpdateState.UpdateAvailable)
        assertTrue(vm.showUpdateDialog.value)

        // Tap Later
        vm.dismissUpdateLater()
        assertFalse(vm.showUpdateDialog.value)
        assertEquals(newerCode, vm.repository.getDismissedUpdateVersionCode())
        assertEquals("Prem", vm.repository.userPreferences.value.userName)

        // Subsequent non-user auto check skips reopening dialog for dismissed version
        vm.checkForUpdates(userInitiated = false, customManager = manager, apiBaseUrl = "https://api.github.test")
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.showUpdateDialog.value)
    }

    @Test
    fun testUpToDateReleasePostsNoUpdateNotification() = runBlocking {
        val upToDateJson = """
            {
              "tag_name": "v${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
              "name": "Siri AI Assistant v${BuildConfig.VERSION_NAME}",
              "body": "Version Code: ${BuildConfig.VERSION_CODE}",
              "assets": []
            }
        """.trimIndent()
        val manager = AppUpdateManager(app, createMockClient(upToDateJson))

        val outcome = manager.checkForLatestRelease(
            repoSlug = "princepremi7461889152-oss/Siri-ai-apk",
            currentVersionCode = BuildConfig.VERSION_CODE,
            currentVersionName = BuildConfig.VERSION_NAME,
            apiBaseUrl = "https://api.github.test"
        )
        assertTrue(outcome is UpdateCheckOutcome.UpToDate)

        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancelAll()

        val vm = SiriViewModel(app)
        vm.checkForUpdates(userInitiated = false, customManager = manager, apiBaseUrl = "https://api.github.test")
        var waitCycles = 0
        while (vm.appUpdateState.value !is AppUpdateState.UpToDate && waitCycles < 30) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            waitCycles++
        }

        assertTrue(vm.appUpdateState.value is AppUpdateState.UpToDate)
        assertFalse("Should not auto-open update dialog when already up to date", vm.showUpdateDialog.value)
        val postedUpdateNotif = shadowOf(nm).allNotifications.any {
            it.channelId == AppUpdateManager.UPDATE_CHANNEL_ID
        }
        assertFalse("Should not post update notification when already up to date", postedUpdateNotif)
    }
}
