package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.device.BootReceiver
import com.example.device.PermissionManager
import com.example.service.SiriVoiceService
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.PrivacyScreen
import com.example.ui.screens.ProductivityScreen
import com.example.ui.screens.VisionScreen
import com.example.ui.screens.VoiceAssistantScreen
import com.example.ui.theme.SiriAITheme
import com.example.ui.theme.SiriCyan
import com.example.ui.theme.SiriDarkBackground
import com.example.ui.theme.SiriDarkSurface
import com.example.ui.theme.SiriDarkSurfaceVariant
import com.example.ui.theme.SiriTextPrimary
import com.example.ui.theme.SiriTextSecondary
import com.example.viewmodel.SiriViewModel

enum class SiriTab(val title: String, val icon: ImageVector, val tag: String) {
    ASSISTANT("Siri", Icons.Default.Mic, "tab_assistant"),
    VISION("Vision", Icons.Default.CameraAlt, "tab_vision"),
    PRODUCTIVITY("Planner", Icons.Default.CheckCircleOutline, "tab_productivity"),
    AI_STUDIO("Brain", Icons.Default.AutoAwesome, "tab_ai_studio"),
    PRIVACY("Privacy", Icons.Default.Security, "tab_privacy")
}

class MainActivity : ComponentActivity() {

    private val viewModel: SiriViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SiriAITheme {
                MainContent(viewModel = viewModel)
            }
        }

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val shouldListen = intent.getBooleanExtra(SiriVoiceService.EXTRA_TRIGGER_LISTEN, false) ||
            intent.action == SiriVoiceService.ACTION_TRIGGER_LISTEN
        if (shouldListen) {
            viewModel.startListening()
        }
        val shouldResumeService = intent.getBooleanExtra(BootReceiver.EXTRA_RESUME_BACKGROUND_SERVICE, false)
        if (shouldResumeService) {
            viewModel.setBackgroundVoiceServiceEnabled(true)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MainContent(viewModel: SiriViewModel) {
    var selectedTab by remember { mutableStateOf(SiriTab.ASSISTANT) }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var activeRequestedPermission by remember { mutableStateOf<String?>(null) }
    var rationalePermission by remember { mutableStateOf<String?>(null) }
    var permanentlyDeniedPermission by remember { mutableStateOf<String?>(null) }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val perm = activeRequestedPermission
        activeRequestedPermission = null
        if (perm != null) {
            viewModel.onPermissionResult(perm, granted)
        }
    }

    // Coordinate lifecycle transitions & recheck live permission state after Settings return
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.refreshPermissionStatus()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Run one-time first-launch check (never repeats on subsequent app opens)
    LaunchedEffect(viewModel) {
        viewModel.runFirstLaunchPermissionCheck()
    }

    // Handle on-demand single-permission requests cleanly
    LaunchedEffect(viewModel) {
        viewModel.permissionRequestEvents.collect { permission ->
            if (PermissionManager.isGranted(context, permission)) {
                viewModel.refreshPermissionStatus()
                return@collect
            }
            val shouldShowRationale = PermissionManager.shouldShowRationale(activity, permission)
            val alreadyPrompted = viewModel.repository.hasPermissionBeenPrompted(permission)
            when {
                shouldShowRationale -> {
                    rationalePermission = permission
                }
                alreadyPrompted && !shouldShowRationale -> {
                    permanentlyDeniedPermission = permission
                }
                else -> {
                    activeRequestedPermission = permission
                    singlePermissionLauncher.launch(permission)
                }
            }
        }
    }

    rationalePermission?.let { perm ->
        val (title, reason) = PermissionManager.getPermissionTitleAndReason(perm)
        AlertDialog(
            onDismissRequest = { rationalePermission = null },
            containerColor = SiriDarkSurfaceVariant,
            title = { Text(title, color = SiriTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(reason, color = SiriTextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        rationalePermission = null
                        activeRequestedPermission = perm
                        singlePermissionLauncher.launch(perm)
                    },
                    modifier = Modifier.testTag("permission_rationale_allow_button")
                ) {
                    Text("Continue", color = SiriCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.repository.markPermissionPrompted(perm)
                        rationalePermission = null
                    },
                    modifier = Modifier.testTag("permission_rationale_cancel_button")
                ) {
                    Text("Not Now", color = SiriTextSecondary)
                }
            }
        )
    }

    permanentlyDeniedPermission?.let { perm ->
        val (title, reason) = PermissionManager.getPermissionTitleAndReason(perm)
        AlertDialog(
            onDismissRequest = { permanentlyDeniedPermission = null },
            containerColor = SiriDarkSurfaceVariant,
            title = { Text("$title Disabled", color = SiriTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "$reason\n\nAndroid requires enabling this permission manually in App Settings.",
                    color = SiriTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permanentlyDeniedPermission = null
                        if (perm == Manifest.permission.POST_NOTIFICATIONS) {
                            PermissionManager.openNotificationSettings(context)
                        } else {
                            PermissionManager.openAppSettings(context)
                        }
                    },
                    modifier = Modifier.testTag("permission_open_settings_button")
                ) {
                    Text("Open Settings", color = SiriCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { permanentlyDeniedPermission = null },
                    modifier = Modifier.testTag("permission_settings_dismiss_button")
                ) {
                    Text("Dismiss", color = SiriTextSecondary)
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = SiriDarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = SiriDarkSurface,
                contentColor = SiriTextPrimary,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("siri_bottom_nav_bar")
            ) {
                for (tab in SiriTab.values()) {
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) SiriCyan else SiriTextSecondary
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                color = if (isSelected) SiriCyan else SiriTextSecondary
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SiriCyan,
                            unselectedIconColor = SiriTextSecondary,
                            selectedTextColor = SiriCyan,
                            unselectedTextColor = SiriTextSecondary,
                            indicatorColor = SiriDarkSurfaceVariant
                        ),
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                SiriTab.ASSISTANT -> VoiceAssistantScreen(viewModel = viewModel)
                SiriTab.VISION -> VisionScreen(viewModel = viewModel)
                SiriTab.PRODUCTIVITY -> ProductivityScreen(viewModel = viewModel)
                SiriTab.AI_STUDIO -> AiStudioScreen(viewModel = viewModel)
                SiriTab.PRIVACY -> PrivacyScreen(viewModel = viewModel)
            }
        }
    }
}
