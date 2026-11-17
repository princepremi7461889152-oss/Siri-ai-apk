package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiBrainRepository
import com.example.ai.EmotionEngine
import com.example.device.AndroidDeviceController
import com.example.intent.SiriIntentParser
import com.example.model.AiConfig
import com.example.model.AssistantEmotion
import com.example.model.LanguageOption
import com.example.model.VoiceState
import com.example.model.WakeWordOption
import com.example.ui.components.SiriOrb
import com.example.viewmodel.SiriViewModel
import com.example.voice.SiriTextToSpeech
import com.example.voice.WakeWordDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiriUpgradeRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var deviceController: AndroidDeviceController
    private lateinit var parser: SiriIntentParser

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        registerMockLaunchableApp("com.whatsapp", "com.whatsapp.Main", "WhatsApp")
        registerMockLaunchableApp("com.instagram.android", "com.instagram.mainactivity.InstagramMainActivity", "Instagram")
        registerMockLaunchableApp("com.facebook.katana", "com.facebook.katana.LoginActivity", "Facebook")
        registerMockLaunchableApp("com.google.android.youtube", "com.google.android.youtube.HomeActivity", "YouTube")
        registerMockLaunchableApp("com.android.chrome", "com.google.android.apps.chrome.Main", "Chrome")
        registerMockLaunchableApp("org.telegram.messenger", "org.telegram.ui.LaunchActivity", "Telegram")
        registerMockLaunchableApp("com.example.customnotes", "com.example.customnotes.NotesActivity", "Quick Sketch")

        deviceController = AndroidDeviceController(app)
        parser = SiriIntentParser(deviceController)
    }

    private fun registerMockLaunchableApp(packageName: String, activityName: String, label: String) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(packageName, activityName)
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            this.nonLocalizedLabel = label
        }
        val actInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = activityName
            this.applicationInfo = appInfo
            this.nonLocalizedLabel = label
            this.exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = actInfo
            this.nonLocalizedLabel = label
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        pm.addActivityIfNotPresent(component)
        pm.addOrUpdateActivity(actInfo)
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.addIntentFilterForActivity(component, filter)
        pm.addResolveInfoForIntent(intent, resolveInfo)
    }

    // =========================================================================
    // 1. EMOTIONAL INTELLIGENCE TESTS
    // =========================================================================

    @Test
    fun testEmotionDetectionAcrossLanguages() {
        assertEquals(AssistantEmotion.HAPPINESS, EmotionEngine.detectEmotion("I am so happy today, thank you!"))
        assertEquals(AssistantEmotion.SADNESS, EmotionEngine.detectEmotion("I feel really sad and stressed about today"))
        assertEquals(AssistantEmotion.SADNESS, EmotionEngine.detectEmotion("Main aaj bohot udaas aur pareshan hoon"))
        assertEquals(AssistantEmotion.EXCITEMENT, EmotionEngine.detectEmotion("Woohoo I won the championship!"))
        assertEquals(AssistantEmotion.EXCITEMENT, EmotionEngine.detectEmotion("वाह मज़ा आ गया कमाल हो गया!"))
        assertEquals(AssistantEmotion.SURPRISE, EmotionEngine.detectEmotion("Whoa really? No way!"))
        assertEquals(AssistantEmotion.CURIOSITY, EmotionEngine.detectEmotion("Why does the sky look blue?"))
        assertEquals(AssistantEmotion.CURIOSITY, EmotionEngine.detectEmotion("ब्लैक होल कैसे बनता है?"))
        assertEquals(AssistantEmotion.SERIOUSNESS, EmotionEngine.detectEmotion("Urgent security warning about my account"))
    }

    @Test
    fun testEmotionTagParsingAndStripping() {
        val (emo1, text1) = EmotionEngine.parseTaggedResponse("[EMOTION:EXCITEMENT] That is incredible news!", "I got promoted")
        assertEquals(AssistantEmotion.EXCITEMENT, emo1)
        assertEquals("That is incredible news!", text1)

        val (emo2, text2) = EmotionEngine.parseTaggedResponse("I'm really sorry you're dealing with that.", "I failed my test and feel sad")
        assertEquals(AssistantEmotion.SADNESS, emo2)
        assertEquals("I'm really sorry you're dealing with that.", text2)
    }

    @Test
    fun testNonRepeatingNaturalResponses() {
        val choices = listOf("Hey there!", "Hi! What's up?", "Hello! Glad you're here.")
        val first = EmotionEngine.pickNonRepeating("test_key", choices)
        val second = EmotionEngine.pickNonRepeating("test_key", choices)
        assertNotEquals(first, second)
    }

    @Test
    fun testEmotionProsodyModulation() {
        val tts = SiriTextToSpeech(app) {}
        val (neutralPitch, neutralRate) = tts.computeEmotionProsody(AssistantEmotion.NEUTRAL)
        val (excitedPitch, excitedRate) = tts.computeEmotionProsody(AssistantEmotion.EXCITEMENT)
        val (sadPitch, sadRate) = tts.computeEmotionProsody(AssistantEmotion.SADNESS)

        assertTrue("Excited pitch should exceed neutral", excitedPitch > neutralPitch)
        assertTrue("Excited rate should exceed neutral", excitedRate > neutralRate)
        assertTrue("Sad pitch should be lower than neutral", sadPitch < neutralPitch)
        assertTrue("Sad rate should be slower than neutral", sadRate < neutralRate)

        val cleaned = tts.sanitizeForSpeech("[EMOTION:HAPPINESS] **Great** news! Check https://example.com")
        assertFalse(cleaned.contains("EMOTION"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://"))
        tts.shutdown()
    }

    // =========================================================================
    // 2. APP OPENING TESTS (ENGLISH, HINGLISH, HINDI, FUZZY & VERIFIED)
    // =========================================================================

    @Test
    fun testOpenPopularAppsInEnglishAndVerifyLaunchedIntent() {
        val apps = listOf(
            "open whatsapp" to "com.whatsapp",
            "launch instagram" to "com.instagram.android",
            "open facebook" to "com.facebook.katana",
            "open youtube" to "com.google.android.youtube",
            "open chrome" to "com.android.chrome",
            "open telegram" to "org.telegram.messenger",
            "open quick sketch" to "com.example.customnotes"
        )

        for ((command, expectedPkg) in apps) {
            val result = parser.parseAndExecute(command)
            assertNotNull("Command '$command' should parse", result)
            assertEquals("OPEN_APP", result?.intentName)
            assertTrue("Expected '$command' to succeed: ${result?.feedbackSpeech}", result?.success == true)
            val launched = shadowOf(app).nextStartedActivity
            assertNotNull("Expected activity launch for $command", launched)
            assertEquals("Wrong package launched for '$command'", expectedPkg, launched.`package` ?: launched.component?.packageName)
        }
    }

    @Test
    fun testOpenAppsInHinglishAndHindi() {
        val commands = listOf(
            "whatsapp kholo" to "com.whatsapp",
            "facebook chalu karo" to "com.facebook.katana",
            "mera instagram open karo" to "com.instagram.android",
            "व्हाट्सएप खोलो" to "com.whatsapp",
            "फेसबुक चालू करो" to "com.facebook.katana",
            "यूट्यूब खोलो" to "com.google.android.youtube",
            "क्रोम ओपन करो" to "com.android.chrome"
        )

        for ((command, expectedPkg) in commands) {
            val result = parser.parseAndExecute(command)
            assertNotNull("Hindi/Hinglish command '$command' should parse", result)
            assertEquals("OPEN_APP", result?.intentName)
            assertTrue("Command '$command' should succeed: ${result?.feedbackSpeech}", result?.success == true)
            val launched = shadowOf(app).nextStartedActivity
            assertNotNull("Expected launch intent for '$command'", launched)
            assertEquals(expectedPkg, launched.`package` ?: launched.component?.packageName)
        }
    }

    @Test
    fun testOpenAppFuzzyTypoAndMissingAppVerification() {
        // Speech recognition typo "open watsapp" -> fuzzy matches WhatsApp
        val fuzzyResult = parser.parseAndExecute("open watsapp")
        assertNotNull(fuzzyResult)
        assertTrue(fuzzyResult?.success == true)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals("com.whatsapp", launched.`package` ?: launched.component?.packageName)

        // Truly uninstalled app must report failure instead of fake web search success
        val missingResult = parser.parseAndExecute("open nonexistentappxyz")
        assertNotNull(missingResult)
        assertEquals("OPEN_APP", missingResult?.intentName)
        assertFalse("Missing app should NOT claim success", missingResult?.success == true)
        assertEquals(AssistantEmotion.SERIOUSNESS, missingResult?.emotion)
    }

    // =========================================================================
    // 3. FAST VOICE RESPONSE & VIEWMODEL FLOW TESTS
    // =========================================================================

    @Test
    fun testFastLocalCommandAndEmotionInViewModel() {
        val vm = SiriViewModel(app)
        val start = System.currentTimeMillis()
        vm.handleUserUtterance("Hey Siri, tell me a joke")
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Local intent should execute nearly instantaneously (<500ms), took ${elapsed}ms", elapsed < 500)
        assertEquals(AssistantEmotion.HAPPINESS, vm.currentEmotion.value)
        assertTrue(vm.lastAssistantSpeech.value.isNotBlank())
    }

    @Test
    fun testAiBrainOfflineFastFallbackHasEmotion() = runBlocking {
        val brain = AiBrainRepository(app)
        val res = brain.generateResponseStream(
            prompt = "Why am I feeling so sad today?",
            config = AiConfig(customApiKey = ""),
            language = LanguageOption.ENGLISH
        )
        assertTrue(res.latencyMs < 250L)
        assertEquals(AssistantEmotion.SADNESS, res.emotion)
        assertTrue(res.text.isNotBlank())
    }

    // =========================================================================
    // 4. ACCURATE "HEY SIRI" WAKE-WORD DETECTION TESTS
    // =========================================================================

    @Test
    fun testHeySiriAcceptsGenuineWakeWordAndExtractsCommand() {
        val detector = WakeWordDetector()
        // Simulate quiet room ambient floor + clear spoken speech
        repeat(5) { detector.onRmsSample(0.08f, isSpeechActive = false) }
        detector.onRmsSample(0.65f, isSpeechActive = true)

        val evalWithCmd = detector.evaluate(
            nBestMatches = listOf("Hey Siri open WhatsApp"),
            confidenceScores = floatArrayOf(0.92f),
            wakeWordOption = WakeWordOption.HEY_SIRI
        )
        assertTrue(evalWithCmd.detected)
        assertEquals("open WhatsApp", evalWithCmd.commandText)

        detector.resetUtteranceMetrics()
        detector.onRmsSample(0.55f, isSpeechActive = true)
        val evalWakeOnly = detector.evaluate(
            nBestMatches = listOf("Hey Siri"),
            confidenceScores = floatArrayOf(0.88f),
            wakeWordOption = WakeWordOption.HEY_SIRI
        )
        assertTrue(evalWakeOnly.detected)
        assertEquals("", evalWakeOnly.commandText)

        detector.resetUtteranceMetrics()
        detector.onRmsSample(0.60f, isSpeechActive = true)
        val evalHindi = detector.evaluate(
            nBestMatches = listOf("हे सिरी यूट्यूब खोलो"),
            confidenceScores = floatArrayOf(0.85f),
            wakeWordOption = WakeWordOption.HEY_SIRI
        )
        assertTrue(evalHindi.detected)
        assertEquals("यूट्यूब खोलो", evalHindi.commandText)
    }

    @Test
    fun testHeySiriRejectsBackgroundConversationTvAndFalsePositives() {
        val detector = WakeWordDetector()
        repeat(5) { detector.onRmsSample(0.10f, isSpeechActive = false) }
        detector.onRmsSample(0.60f, isSpeechActive = true)

        val falseInputs = listOf(
            "Hello how are you doing today",
            "I was telling my friend hey siri is on my phone",
            "Seriously I don't think that's true",
            "Hey sorry I missed your call",
            "Hey Sarah let's watch TV",
            "Siri is an apple feature in this movie" // bare 'siri' mid-sentence must NOT trigger HEY_SIRI
        )

        for (phrase in falseInputs) {
            val eval = detector.evaluate(
                nBestMatches = listOf(phrase),
                confidenceScores = floatArrayOf(0.95f),
                wakeWordOption = WakeWordOption.HEY_SIRI
            )
            assertFalse("Should reject non-wake phrase: '$phrase'", eval.detected)
        }
    }

    @Test
    fun testHeySiriRejectsLowConfidenceNoisyBackgroundAndSelfEcho() {
        val detector = WakeWordDetector()

        // 1. Low confidence rejection
        detector.onRmsSample(0.60f, isSpeechActive = true)
        val lowConfEval = detector.evaluate(
            nBestMatches = listOf("Hey Siri"),
            confidenceScores = floatArrayOf(0.41f),
            wakeWordOption = WakeWordOption.HEY_SIRI
        )
        assertFalse("Low confidence should be rejected", lowConfEval.detected)

        // 2. Distant TV / low-energy background bleed rejection
        detector.resetUtteranceMetrics()
        repeat(5) { detector.onRmsSample(0.32f, isSpeechActive = false) } // noisy TV room
        detector.onRmsSample(0.34f, isSpeechActive = true) // barely above noise floor
        val noisyEval = detector.evaluate(
            nBestMatches = listOf("Hey Siri"),
            confidenceScores = floatArrayOf(0.90f),
            wakeWordOption = WakeWordOption.HEY_SIRI
        )
        assertFalse("Low-SNR background noise should be rejected", noisyEval.detected)

        // 3. Self-echo rejection immediately after TTS finishes
        detector.resetUtteranceMetrics()
        detector.onRmsSample(0.75f, isSpeechActive = true)
        val now = 10_000L
        detector.markAssistantFinishedSpeaking(now - 200L)
        val echoEval = detector.evaluate(
            nBestMatches = listOf("Hey Siri"),
            confidenceScores = floatArrayOf(0.95f),
            wakeWordOption = WakeWordOption.HEY_SIRI,
            nowMs = now
        )
        assertFalse("Self-echo within cooldown should be rejected", echoEval.detected)
    }

    // =========================================================================
    // 5. SIRI ORB ANIMATION & TTS VOCAL SYNC TESTS
    // =========================================================================

    @Test
    fun testTtsWordVocalEnergyTracksEmotionsAndCadence() {
        val tts = SiriTextToSpeech(app) {}
        val excitedEnergy = tts.computeWordVocalEnergy("Congratulations!", AssistantEmotion.EXCITEMENT)
        val neutralEnergy = tts.computeWordVocalEnergy("Hello", AssistantEmotion.NEUTRAL)
        val sadEnergy = tts.computeWordVocalEnergy("sorry", AssistantEmotion.SADNESS)
        val pauseEnergy = tts.computeWordVocalEnergy("   ", AssistantEmotion.NEUTRAL)

        assertTrue("Excited energy should exceed neutral", excitedEnergy > neutralEnergy)
        assertTrue("Sad energy should be softer than neutral", sadEnergy < neutralEnergy)
        assertTrue("Pause energy should drop low", pauseEnergy < 0.20f)
        tts.shutdown()
    }

    @Test
    fun testSiriOrbSmoothTransitionsAcrossAllStatesAndEmotions() {
        var voiceState by mutableStateOf(VoiceState.IDLE)
        var audioLevel by mutableFloatStateOf(0.08f)
        var emotion by mutableStateOf(AssistantEmotion.NEUTRAL)
        var reducedMotion by mutableStateOf(false)

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            SiriOrb(
                voiceState = voiceState,
                audioLevel = audioLevel,
                emotion = emotion,
                reducedMotion = reducedMotion
            )
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        val stateEmotionSequence = listOf(
            Triple(VoiceState.IDLE, AssistantEmotion.NEUTRAL, 0.08f),
            Triple(VoiceState.LISTENING, AssistantEmotion.NEUTRAL, 0.85f),
            Triple(VoiceState.THINKING, AssistantEmotion.CURIOSITY, 0.15f),
            Triple(VoiceState.SPEAKING, AssistantEmotion.HAPPINESS, 0.72f),
            Triple(VoiceState.SPEAKING, AssistantEmotion.EXCITEMENT, 0.92f),
            Triple(VoiceState.SPEAKING, AssistantEmotion.SADNESS, 0.35f),
            Triple(VoiceState.IDLE, AssistantEmotion.SURPRISE, 0.25f),
            Triple(VoiceState.IDLE, AssistantEmotion.SERIOUSNESS, 0.10f),
            Triple(VoiceState.ERROR, AssistantEmotion.SERIOUSNESS, 0.05f)
        )

        for ((nextState, nextEmotion, nextAudio) in stateEmotionSequence) {
            composeTestRule.runOnUiThread {
                voiceState = nextState
                emotion = nextEmotion
                audioLevel = nextAudio
            }
            composeTestRule.mainClock.advanceTimeBy(180L)
            composeTestRule.onNodeWithTag("siri_voice_orb")
                .assertContentDescriptionContains("state ${nextState.name}", substring = true)
                .assertContentDescriptionContains("emotion ${nextEmotion.displayName}", substring = true)
        }

        // Verify reducedMotion mode pauses the frame loop cleanly and still renders
        composeTestRule.runOnUiThread { reducedMotion = true }
        composeTestRule.mainClock.advanceTimeBy(64L)
        composeTestRule.onNodeWithTag("siri_voice_orb").assertExists()
    }
}
