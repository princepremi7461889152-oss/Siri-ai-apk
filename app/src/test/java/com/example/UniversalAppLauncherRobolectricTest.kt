package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.device.AndroidDeviceController
import com.example.intent.SiriIntentParser
import com.example.model.AssistantEmotion
import com.example.service.SiriVoiceService
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
class UniversalAppLauncherRobolectricTest {

    private lateinit var app: Application
    private lateinit var controller: AndroidDeviceController
    private lateinit var parser: SiriIntentParser

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        registerMockLaunchableApp("com.google.android.youtube", "com.google.android.youtube.HomeActivity", "YouTube", isSystem = false)
        registerMockLaunchableApp("com.whatsapp", "com.whatsapp.Main", "WhatsApp", isSystem = false)
        registerMockLaunchableApp("com.instagram.android", "com.instagram.mainactivity.InstagramMainActivity", "Instagram", isSystem = false)
        registerMockLaunchableApp("com.android.chrome", "com.google.android.apps.chrome.Main", "Chrome", isSystem = true)
        registerMockLaunchableApp("com.google.android.calculator", "com.android.calculator2.Calculator", "Calculator", isSystem = true)
        registerMockLaunchableApp("com.google.android.apps.photos", "com.google.android.apps.photos.home.HomeActivity", "Photos", isSystem = true)
        registerMockLaunchableApp("com.simplemobiletools.gallery", "com.simplemobiletools.gallery.activities.MainActivity", "Gallery", isSystem = false)
        registerMockLaunchableApp("com.canva.editor", "com.canva.editor.MainActivity", "Canva", isSystem = false)
        registerMockLaunchableApp("in.amazon.mShop.android.shopping", "com.amazon.mShop.home.HomeActivity", "Amazon Shopping", isSystem = false)
        registerMockLaunchableApp("com.amazon.avod.thirdpartyclient", "com.amazon.avod.LauncherActivity", "Amazon Prime Video", isSystem = false)
        registerMockSettingsActivity()

        controller = AndroidDeviceController(app)
        parser = SiriIntentParser(controller)
    }

    @After
    fun tearDown() {
        controller.release()
    }

    private fun registerMockLaunchableApp(
        packageName: String,
        activityName: String,
        label: String,
        isSystem: Boolean
    ) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(packageName, activityName)
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            this.nonLocalizedLabel = label
            this.flags = if (isSystem) ApplicationInfo.FLAG_SYSTEM else 0
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
        val pkgIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val globalMainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.addActivityIfNotPresent(component)
        pm.addOrUpdateActivity(actInfo)
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.addIntentFilterForActivity(component, filter)
        pm.addResolveInfoForIntent(pkgIntent, resolveInfo)
        pm.addResolveInfoForIntent(globalMainIntent, resolveInfo)
    }

    @Suppress("DEPRECATION")
    private fun registerMockSettingsActivity() {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName("com.android.settings", "com.android.settings.Settings")
        val appInfo = ApplicationInfo().apply {
            packageName = "com.android.settings"
            name = "Settings"
            nonLocalizedLabel = "Settings"
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        val actInfo = ActivityInfo().apply {
            packageName = "com.android.settings"
            name = "com.android.settings.Settings"
            applicationInfo = appInfo
            nonLocalizedLabel = "Settings"
            exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = actInfo
            nonLocalizedLabel = "Settings"
        }
        pm.addActivityIfNotPresent(component)
        pm.addOrUpdateActivity(actInfo)
        pm.addIntentFilterForActivity(component, IntentFilter(Settings.ACTION_SETTINGS))
        pm.addResolveInfoForIntent(Intent(Settings.ACTION_SETTINGS), resolveInfo)
    }

    private fun unregisterMockLaunchableApp(packageName: String, activityName: String) {
        val pm = shadowOf(app.packageManager)
        pm.removePackage(packageName)
        pm.removeResolveInfosForIntent(
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            packageName
        )
        pm.removeResolveInfosForIntent(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            },
            packageName
        )
    }

    @Test
    fun testAllPromptVoiceCommandVariationsInHindiHinglishEnglish() {
        val commandExpectations = listOf(
            "Siri, YouTube kholo." to "com.google.android.youtube",
            "WhatsApp open karo." to "com.whatsapp",
            "Instagram chalao." to "com.instagram.android",
            "Chrome kholo." to "com.android.chrome",
            "Mere phone mein calculator kholo." to "com.google.android.calculator",
            "Open my gallery." to "com.simplemobiletools.gallery"
        )

        for ((command, expectedPkg) in commandExpectations) {
            val res = parser.parseAndExecute(command)
            assertNotNull("Command '$command' should parse", res)
            assertEquals("OPEN_APP", res?.intentName)
            assertTrue("Command '$command' should succeed: ${res?.feedbackSpeech}", res?.success == true)
            val launched = shadowOf(app).nextStartedActivity
            assertNotNull("Expected activity launch for '$command'", launched)
            assertEquals(expectedPkg, launched.`package` ?: launched.component?.packageName)
        }

        // Open settings
        val settingsRes = parser.parseAndExecute("Open settings.")
        assertNotNull(settingsRes)
        assertEquals("OPEN_APP", settingsRes?.intentName)
        assertTrue(settingsRes?.success == true)
        val settingsIntent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_SETTINGS, settingsIntent.action)
    }

    @Test
    fun testArbitraryThirdPartyAppViaHindiDevanagariAndPhoneticMatching() {
        // Devanagari transliteration of an unlisted third-party app ("Canva")
        val hindiRes = parser.parseAndExecute("कैनवा खोलो")
        assertNotNull(hindiRes)
        assertTrue("Expected Devanagari 'कैनवा खोलो' to launch Canva: ${hindiRes?.feedbackSpeech}", hindiRes?.success == true)
        val launchedHindi = shadowOf(app).nextStartedActivity
        assertEquals("com.canva.editor", launchedHindi.`package` ?: launchedHindi.component?.packageName)

        // Phonetic/misspelling variation ("kainva open karo")
        val phoneticRes = parser.parseAndExecute("kainva open karo")
        assertNotNull(phoneticRes)
        assertTrue("Expected phonetic 'kainva' to launch Canva: ${phoneticRes?.feedbackSpeech}", phoneticRes?.success == true)
        val launchedPhonetic = shadowOf(app).nextStartedActivity
        assertEquals("com.canva.editor", launchedPhonetic.`package` ?: launchedPhonetic.component?.packageName)
    }

    @Test
    fun testDisambiguationWhenMultipleAppsMatchAndVoiceFollowUpSelection() {
        val ambiguousRes = parser.parseAndExecute("open amazon")
        assertNotNull(ambiguousRes)
        assertEquals("OPEN_APP_DISAMBIGUATION", ambiguousRes?.intentName)
        assertTrue(ambiguousRes?.appChoices?.size == 2)
        assertTrue(ambiguousRes?.feedbackSpeech?.contains("Amazon Shopping") == true)
        assertTrue(ambiguousRes?.feedbackSpeech?.contains("Amazon Prime Video") == true)

        // User speaks follow-up selection by name
        val selectedRes = parser.parseAndExecute("Amazon Prime Video")
        assertNotNull(selectedRes)
        assertEquals("OPEN_APP", selectedRes?.intentName)
        assertTrue(selectedRes?.success == true)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals("com.amazon.avod.thirdpartyclient", launched.`package` ?: launched.component?.packageName)
    }

    @Test
    fun testDynamicAppInstallAndUninstallBroadcastUpdatesIndex() {
        // Before installation, Notion is missing
        val beforeRes = parser.parseAndExecute("open notion")
        assertNotNull(beforeRes)
        assertFalse(beforeRes?.success == true)
        assertEquals(AssistantEmotion.SERIOUSNESS, beforeRes?.emotion)

        // Install Notion dynamically and refresh via PACKAGE_ADDED broadcast
        registerMockLaunchableApp("notion.id", "notion.id.MainActivity", "Notion", isSystem = false)
        app.sendBroadcast(
            Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:notion.id")).apply {
                setPackage(app.packageName)
            }
        )
        // Run pending main looper tasks so dynamic BroadcastReceiver processes
        shadowOf(app.mainLooper).idle()

        val afterInstallRes = parser.parseAndExecute("Siri, Notion kholo")
        assertNotNull(afterInstallRes)
        assertTrue("Newly installed Notion should launch automatically", afterInstallRes?.success == true)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals("notion.id", launched.`package` ?: launched.component?.packageName)

        // Uninstall Notion and send PACKAGE_REMOVED broadcast
        unregisterMockLaunchableApp("notion.id", "notion.id.MainActivity")
        app.sendBroadcast(
            Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:notion.id")).apply {
                setPackage(app.packageName)
            }
        )
        shadowOf(app.mainLooper).idle()

        val afterRemoveRes = parser.parseAndExecute("open notion")
        assertNotNull(afterRemoveRes)
        assertFalse("Uninstalled app should no longer launch", afterRemoveRes?.success == true)
    }

    @Test
    fun testBackgroundVoiceServiceExecutesAppLaunchCommandSafely() {
        val serviceController = Robolectric.buildService(SiriVoiceService::class.java).create()
        val service = serviceController.get()
        service.handleBackgroundCommand("Siri, YouTube kholo.")

        val launched = shadowOf(app).nextStartedActivity
        assertNotNull("Background service should launch YouTube when permitted", launched)
        assertEquals("com.google.android.youtube", launched.`package` ?: launched.component?.packageName)

        serviceController.destroy()
    }
}
