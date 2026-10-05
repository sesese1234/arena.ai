package com.nuvio.app.agent.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.agent.AgentController
import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.runtime.ChatItem
import com.nuvio.app.agent.runtime.ChatItemKind
import kotlinx.serialization.json.JsonElement

@Composable
internal fun AgentChat(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val state by AgentController.session.state.collectAsStateWithLifecycle()
    val settings by AgentController.settings.settings.collectAsStateWithLifecycle()
    val showReasoning = settings.showReasoning
    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = if (input.isBlank()) spoken else "$input $spoken"
        }
    }
    val voiceAvailable = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }

    LaunchedEffect(state.items.size, state.items.lastOrNull()?.text?.length) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    val selection = settings.selection
    val modelLabel = selection?.let { sel ->
        settings.providers.firstOrNull { it.id == sel.providerId }?.let { p ->
            "${p.name} · ${p.models.firstOrNull { it.id == sel.modelId }?.name ?: sel.modelId}"
        }
    } ?: "No model selected"

    Column(Modifier.fillMaxSize().then(if (expanded) Modifier.statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    modelLabel + if (state.running) " · step ${state.step}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenSettings).testTag("agent_model_label"),
                )
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Rounded.History, "Conversation history") }
            IconButton(onClick = { AgentController.newConversation() }, modifier = Modifier.testTag("agent_new_chat")) { Icon(Icons.Rounded.Add, "New conversation") }
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("agent_settings")) { Icon(Icons.Rounded.Settings, "Assistant settings") }
            IconButton(onClick = onToggleExpanded) { Icon(if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, if (expanded) "Compact" else "Full screen") }
            IconButton(onClick = onClose, modifier = Modifier.testTag("agent_close")) { Icon(Icons.Rounded.Close, "Close assistant") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("agent_messages"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        ) {
            if (state.items.isEmpty()) {
                item { EmptyState(configured = selection != null, onOpenSettings = onOpenSettings, onSuggestion = { input = it }) }
            }
            items(state.items, key = { it.id }) { item -> ChatRow(item, showReasoning) }
            if (state.running && state.items.lastOrNull()?.let { it.kind == ChatItemKind.Assistant && it.running } != true) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Working…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask or tell me what to do…") },
                modifier = Modifier.weight(1f).testTag("agent_input"),
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!state.running && AgentController.send(input)) input = ""
                }),
            )
            if (voiceAvailable && !state.running) {
                IconButton(onClick = {
                    voiceLauncher.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I do?"),
                    )
                }) { Icon(Icons.Rounded.Mic, "Voice input") }
            }
            Spacer(Modifier.size(4.dp))
            if (state.running) {
                FilledIconButton(onClick = { AgentController.stop() }, modifier = Modifier.testTag("agent_stop")) { Icon(Icons.Rounded.Stop, "Stop") }
            } else {
                FilledIconButton(
                    onClick = { if (AgentController.send(input)) input = "" },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.testTag("agent_send"),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
        }
    }

    if (showHistory) HistoryDialog(onDismiss = { showHistory = false })
}

@Composable
private fun EmptyState(configured: Boolean, onOpenSettings: () -> Unit, onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "I can drive the whole app: find and open titles, play streams, switch subtitles, manage your library and addons, change settings, and search the web.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!configured) {
            TextButton(onClick = onOpenSettings) { Text("Set up a model provider first →") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Find The Matrix and open its details",
                "What's on my continue-watching list?",
                "Install the Cinemeta addon",
                "Turn on English subtitles",
            ).forEach { suggestion ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.clickable { onSuggestion(suggestion) }) {
                    Text(suggestion, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, showReasoning: Boolean) {
    when (item.kind) {
        ChatItemKind.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), color = MaterialTheme.colorScheme.primary) {
                Text(item.text, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 300.dp).testTag("agent_user_message"))
            }
        }
        ChatItemKind.Assistant -> Column(Modifier.fillMaxWidth()) {
            if (showReasoning && item.reasoning.isNotBlank()) {
                Text(item.reasoning.takeLast(600), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
            if (item.text.isNotBlank() || item.running) {
                Surface(shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        item.text.ifBlank { "…" },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { contentDescription = "Assistant: ${item.text}" }.testTag("agent_assistant_message"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        ChatItemKind.ToolCall -> ToolCallCard(item)
        ChatItemKind.Notice -> Text(item.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 4.dp))
        ChatItemKind.Error -> Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp).testTag("agent_error"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(item.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToolCallCard(item: ChatItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable { open = !open }.testTag("agent_tool_call"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    item.isError -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Rounded.Build, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    "${item.toolName}(${compactArgs(item.arguments)})",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Tool ${item.toolName}" },
                )
                item.durationMs?.let { Text("${it}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", modifier = Modifier.size(18.dp))
            }
            if (open) {
                Text("Arguments", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
                Text(pretty(item.arguments), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("Result", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp))
                Text(pretty(item.result).take(4_000), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun HistoryDialog(onDismiss: () -> Unit) {
    val history by AgentController.history.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversations") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                if (history.isEmpty()) item { Text("No saved conversations yet.") }
                items(history, key = { it.id }) { conversation ->
                    Row(Modifier.fillMaxWidth().clickable { AgentController.openConversation(conversation.id); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(conversation.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        IconButton(onClick = { AgentController.deleteConversation(conversation.id) }) { Icon(Icons.Rounded.Close, "Delete conversation") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun compactArgs(json: String?): String {
    val text = json.orEmpty().trim().removePrefix("{").removeSuffix("}")
    return if (text.length > 60) text.take(57) + "…" else text
}

private fun pretty(json: String?): String {
    if (json.isNullOrBlank()) return "—"
    return runCatching {
        val element = AgentJson.parseToJsonElement(json)
        PrettyJson.encodeToString(JsonElement.serializer(), element)
    }.getOrDefault(json)
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
