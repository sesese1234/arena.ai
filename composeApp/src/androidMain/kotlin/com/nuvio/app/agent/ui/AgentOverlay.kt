package com.nuvio.app.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.agent.AgentBridge
import com.nuvio.app.agent.AgentController
import com.nuvio.app.navigation.PlayerRoute

internal val AgentColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1A33),
    secondary = Color(0xFFB9C8E8),
    surface = Color(0xFF15181E),
    surfaceVariant = Color(0xFF232833),
    background = Color(0xFF0F1115),
    error = Color(0xFFFF8A80),
)

/**
 * Root overlay that makes the assistant reachable from every screen: a floating button, the chat
 * panel (compact so the app stays visible while the agent works, or full screen), the agent
 * settings, and confirmation dialogs for destructive actions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AgentOverlay() {
    if (!AgentController.isInitialized) return
    val chatOpen by AgentController.chatOpen.collectAsStateWithLifecycle()
    val settings by AgentController.settings.settings.collectAsStateWithLifecycle()
    val navigation by AgentBridge.navigation.collectAsStateWithLifecycle()
    val pending by AgentController.pendingConfirmation.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val inPlayer = navigation?.currentRoute is PlayerRoute
    val appReady = navigation != null

    MaterialTheme(colorScheme = AgentColors) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            if (appReady && !chatOpen && settings.showFloatingButton) {
                FloatingActionButton(
                    onClick = { AgentController.openChat() },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(if (inPlayer) Alignment.TopEnd else Alignment.BottomEnd)
                        .then(if (inPlayer) Modifier.statusBarsPadding().padding(top = 8.dp, end = 72.dp).alpha(0.55f) else Modifier.navigationBarsPadding().padding(end = 16.dp, bottom = 96.dp))
                        .size(if (inPlayer) 40.dp else 52.dp)
                        .testTag("agent_fab")
                        .semantics { contentDescription = "Open assistant" },
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                }
            }

            AnimatedVisibility(
                visible = chatOpen,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BackHandler(enabled = chatOpen) {
                    if (showSettings) showSettings = false else AgentController.closeChat()
                }
                Surface(
                    shape = if (expanded || showSettings) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
                    tonalElevation = 6.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (expanded || showSettings) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.58f))
                        .testTag("agent_panel"),
                ) {
                    if (showSettings) {
                        AgentSettingsScreen(onClose = { showSettings = false })
                    } else {
                        AgentChat(
                            expanded = expanded,
                            onToggleExpanded = { expanded = !expanded },
                            onOpenSettings = { showSettings = true },
                            onClose = { AgentController.closeChat() },
                        )
                    }
                }
            }

            pending?.let { confirmation ->
                AlertDialog(
                    onDismissRequest = { AgentController.answerConfirmation(false) },
                    title = { Text(confirmation.request.title) },
                    text = { Text(confirmation.request.message) },
                    confirmButton = {
                        TextButton(
                            onClick = { AgentController.answerConfirmation(true) },
                            colors = if (confirmation.request.destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
                            modifier = Modifier.testTag("agent_confirm_ok"),
                        ) { Text(confirmation.request.confirmLabel) }
                    },
                    dismissButton = {
                        TextButton(onClick = { AgentController.answerConfirmation(false) }, modifier = Modifier.testTag("agent_confirm_cancel")) { Text("Cancel") }
                    },
                    modifier = Modifier.testTag("agent_confirm_dialog"),
                )
            }
        }
    }
}
