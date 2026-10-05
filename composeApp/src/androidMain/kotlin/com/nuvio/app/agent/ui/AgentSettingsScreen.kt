package com.nuvio.app.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.agent.AgentController
import com.nuvio.app.agent.catalog.CatalogProvider
import com.nuvio.app.agent.catalog.ProviderMapping
import com.nuvio.app.agent.config.SearchBackend
import com.nuvio.app.agent.llm.ApiKind
import com.nuvio.app.agent.llm.LlmClientFactory
import com.nuvio.app.agent.llm.ModelConfig
import com.nuvio.app.agent.llm.ProviderConfig
import kotlinx.coroutines.launch

private sealed interface SettingsPane {
    data object Main : SettingsPane
    data object Catalog : SettingsPane
    data class Edit(val providerId: String?) : SettingsPane
}

@Composable
internal fun AgentSettingsScreen(onClose: () -> Unit) {
    var pane by remember { mutableStateOf<SettingsPane>(SettingsPane.Main) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (pane == SettingsPane.Main) onClose() else pane = SettingsPane.Main }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text(
                when (val p = pane) {
                    SettingsPane.Main -> "Assistant settings"
                    SettingsPane.Catalog -> "Add provider"
                    is SettingsPane.Edit -> if (p.providerId == null) "Custom provider" else "Edit provider"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()
        when (val p = pane) {
            SettingsPane.Main -> MainPane(onAdd = { pane = SettingsPane.Catalog }, onEdit = { pane = SettingsPane.Edit(it) })
            SettingsPane.Catalog -> CatalogPane(
                onPicked = { pane = SettingsPane.Edit(it) },
                onCustom = { pane = SettingsPane.Edit(null) },
            )
            is SettingsPane.Edit -> ProviderEditor(p.providerId, onDone = { pane = SettingsPane.Main })
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
private fun MainPane(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val store = AgentController.settings
    val settings by store.settings.collectAsStateWithLifecycle()
    val catalog by AgentController.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshStatus by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ProviderConfig?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionTitle("Model")
        if (settings.providers.isEmpty()) {
            Text("No providers yet. Add one from the models.dev catalog (OpenAI, Anthropic, Google, DeepSeek, OpenRouter, Groq, Bedrock, Azure, Vertex…) or a custom OpenAI-compatible endpoint such as Ollama or LM Studio.", style = MaterialTheme.typography.bodySmall)
        }
        settings.providers.forEach { provider ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, fontWeight = FontWeight.SemiBold)
                            val missing = store.resolve(com.nuvio.app.agent.llm.ModelSelection(provider.id, provider.models.firstOrNull()?.id ?: "default"))
                                .getOrNull()?.let(LlmClientFactory::missingSecrets).orEmpty()
                            Text(
                                "${provider.kind.label} · " + if (missing.isEmpty()) "credentials set" else "missing ${missing.joinToString { LlmClientFactory.secretLabel(it) }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (missing.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(onClick = { onEdit(provider.id) }) { Icon(Icons.Rounded.Edit, "Edit ${provider.name}") }
                        IconButton(onClick = { confirmRemove = provider }) { Icon(Icons.Rounded.Delete, "Remove ${provider.name}") }
                    }
                    provider.models.forEach { model ->
                        val selected = settings.selection?.providerId == provider.id && settings.selection?.modelId == model.id
                        Row(
                            Modifier.fillMaxWidth().clickable { store.select(provider.id, model.id) }.padding(vertical = 2.dp).testTag("agent_model_${provider.id}_${model.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { store.select(provider.id, model.id) })
                            Text(model.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!model.supportsTools) Text("no tools", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = onAdd, modifier = Modifier.testTag("agent_add_provider")) { Text("Add provider") }
            OutlinedButton(onClick = { onEdit("") }) { Text("Custom endpoint") }
        }

        SectionTitle("Provider catalog (models.dev)")
        Text(
            "${catalog?.providers?.size ?: 0} providers, ${catalog?.providers?.sumOf { it.models.size } ?: 0} models · " +
                (catalog?.generatedAt?.let { "snapshot $it" } ?: "bundled snapshot"),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                refreshStatus = "Refreshing…"
                scope.launch {
                    refreshStatus = AgentController.refreshCatalog().fold(
                        onSuccess = { "Updated: ${it.providers.size} providers" },
                        onFailure = { "Refresh failed: ${it.message}" },
                    )
                }
            }) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Refresh from models.dev")
            }
            refreshStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }

        SectionTitle("Web search")
        SearchBackend.entries.forEach { backend ->
            Row(Modifier.fillMaxWidth().clickable { store.update { it.copy(search = it.search.copy(backend = backend)) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.search.backend == backend, onClick = { store.update { it.copy(search = it.search.copy(backend = backend)) } })
                Text(backend.label)
            }
        }
        when (settings.search.backend) {
            SearchBackend.Brave, SearchBackend.Tavily -> SecretField(
                label = "${settings.search.backend.label} API key",
                isSet = store.searchSecret(settings.search.backend) != null,
                onSave = { store.setSearchSecret(settings.search.backend, it) },
            )
            SearchBackend.Searxng -> TextSetting("SearXNG URL (JSON format enabled)", settings.search.searxngUrl) { url -> store.update { it.copy(search = it.search.copy(searxngUrl = url)) } }
            SearchBackend.SearxngPublic -> TextSetting("Public SearXNG instance", settings.search.publicSearxngUrl) { url -> store.update { it.copy(search = it.search.copy(publicSearxngUrl = url)) } }
            SearchBackend.DuckDuckGo -> Unit
        }
        SwitchRow("Fall back to DuckDuckGo if the backend fails", settings.search.fallbackToDuckDuckGo) { v -> store.update { it.copy(search = it.search.copy(fallbackToDuckDuckGo = v)) } }

        SectionTitle("Behaviour")
        TextSetting("Max steps per request", settings.maxSteps.toString(), KeyboardType.Number) { v -> v.toIntOrNull()?.let { n -> store.update { it.copy(maxSteps = n.coerceIn(1, 50)) } } }
        TextSetting("Temperature (empty = model default)", settings.temperature?.toString().orEmpty(), KeyboardType.Decimal) { v -> store.update { it.copy(temperature = v.toDoubleOrNull()?.coerceIn(0.0, 2.0)) } }
        TextSetting("Max output tokens (empty = model default)", settings.maxOutputTokens?.toString().orEmpty(), KeyboardType.Number) { v -> store.update { it.copy(maxOutputTokens = v.toIntOrNull()?.coerceIn(256, 200_000)) } }
        SwitchRow("Parallel tool calls", settings.parallelToolCalls) { v -> store.update { it.copy(parallelToolCalls = v) } }
        SwitchRow("Show model reasoning", settings.showReasoning) { v -> store.update { it.copy(showReasoning = v) } }
        SwitchRow("Floating assistant button", settings.showFloatingButton) { v -> store.update { it.copy(showFloatingButton = v) } }

        SectionTitle("Memory")
        val memory = remember(settings) { AgentController.memory.all() }
        if (memory.isEmpty()) Text("Nothing remembered yet.", style = MaterialTheme.typography.bodySmall)
        memory.forEach { (k, v) -> Text("• $k: $v", style = MaterialTheme.typography.bodySmall) }
        if (memory.isNotEmpty()) TextButton(onClick = { AgentController.memory.clear(); store.update { it } }) { Text("Forget everything") }
        Spacer(Modifier.size(32.dp))
    }

    confirmRemove?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${provider.name}?") },
            text = { Text("Its settings and stored keys are deleted from this device.") },
            confirmButton = { TextButton(onClick = { store.removeProvider(provider.id); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CatalogPane(onPicked: (String) -> Unit, onCustom: () -> Unit) {
    val catalog by AgentController.catalog.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val providers = catalog?.providers.orEmpty().filter {
        query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it }, label = { Text("Search ${catalog?.providers?.size ?: 0} providers") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("agent_catalog_search"))
        TextButton(onClick = onCustom) { Text("+ Custom OpenAI-compatible endpoint (Ollama, LM Studio, LiteLLM, vLLM…)") }
        LazyColumn(Modifier.weight(1f)) {
            items(providers, key = { it.id }) { provider -> CatalogRow(provider) {
                AgentController.addCatalogProvider(provider.id, defaultModels(provider), apiKey = null)
                onPicked(provider.id)
            } }
        }
    }
}

private fun defaultModels(provider: CatalogProvider): List<String> =
    provider.models.filter { it.toolCall && it.status != "deprecated" }.take(6).map { it.id }
        .ifEmpty { provider.models.take(3).map { it.id } }

@Composable
private fun CatalogRow(provider: CatalogProvider, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp).testTag("agent_catalog_${provider.id}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(provider.name, fontWeight = FontWeight.Medium)
            Text(
                "${ProviderMapping.apiKind(provider.npm).label} · ${provider.models.size} models · ${provider.models.count { it.toolCall }} with tools" +
                    if (!ProviderMapping.isReady(provider)) " · needs base URL" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ProviderEditor(providerId: String?, onDone: () -> Unit) {
    val store = AgentController.settings
    val existing = providerId?.takeIf { it.isNotBlank() }?.let(store::provider)
    val catalogProvider = existing?.catalogId?.let { AgentController.catalog.value?.provider(it) }
    var name by remember { mutableStateOf(existing?.name ?: "Custom (OpenAI-compatible)") }
    var kind by remember { mutableStateOf(existing?.kind ?: ApiKind.OpenAiCompatible) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "http://10.0.2.2:11434/v1") }
    var headers by remember { mutableStateOf(existing?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty()) }
    var variables by remember { mutableStateOf(existing?.variables?.entries?.joinToString("\n") { "${it.key}=${it.value}" }.orEmpty()) }
    var models by remember { mutableStateOf(existing?.models ?: emptyList()) }
    var newModel by remember { mutableStateOf("") }
    val secretNames = existing?.secretNames ?: listOf(ProviderConfig.SECRET_API_KEY)
    val secretEdits = remember { mutableMapOf<String, String>() }
    val id = existing?.id ?: remember { "custom-" + System.currentTimeMillis().toString(36) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        catalogProvider?.doc?.let { Text("Docs: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp)) }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        SectionTitle("Protocol")
        ApiKind.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { kind = option }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(kind == option, { kind = option })
                Text(option.label, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("agent_provider_base_url"))
        Text("Ollama: http://10.0.2.2:11434/v1 (emulator) · LM Studio: http://<pc>:1234/v1 · OpenRouter: https://openrouter.ai/api/v1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)

        SectionTitle("Credentials (encrypted with Android Keystore)")
        secretNames.forEach { secret ->
            SecretField(
                label = LlmClientFactory.secretLabel(secret),
                isSet = existing?.let { store.hasSecret(it.id, secret) } == true,
                multiline = secret == ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT,
                testTag = "agent_secret_$secret",
                onSave = null,
                onChange = { secretEdits[secret] = it },
            )
        }
        OutlinedTextField(variables, { variables = it }, label = { Text("Variables (KEY=value per line: region, resource, project…)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(top = 8.dp))
        OutlinedTextField(headers, { headers = it }, label = { Text("Extra headers (Name: value per line)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(top = 8.dp))

        SectionTitle("Models")
        models.forEachIndexed { index, model ->
            ModelRow(model, onChange = { updated -> models = models.toMutableList().also { it[index] = updated } }, onRemove = { models = models.filterIndexed { i, _ -> i != index } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newModel, { newModel = it }, label = { Text("Add model id") }, singleLine = true, modifier = Modifier.weight(1f).testTag("agent_new_model"))
            IconButton(onClick = {
                val idText = newModel.trim()
                if (idText.isNotEmpty() && models.none { it.id == idText }) {
                    val fromCatalog = catalogProvider?.models?.firstOrNull { it.id == idText }
                    models = models + (fromCatalog?.let { with(ProviderMapping) { it.toModelConfig() } } ?: ModelConfig(id = idText, custom = true))
                }
                newModel = ""
            }) { Icon(Icons.Rounded.Check, "Add model") }
        }
        catalogProvider?.let { cp ->
            Text("Catalog models: " + cp.models.take(40).joinToString { it.id }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }

        Button(
            onClick = {
                val config = ProviderConfig(
                    id = id,
                    name = name.ifBlank { id },
                    kind = kind,
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    headers = parsePairs(headers, ':'),
                    variables = parsePairs(variables, '='),
                    secretNames = secretNames,
                    models = models,
                    catalogId = existing?.catalogId,
                    docUrl = existing?.docUrl,
                    custom = existing?.custom ?: true,
                )
                store.upsertProvider(config)
                secretEdits.forEach { (secret, value) -> if (value.isNotBlank()) store.setSecret(id, secret, value) }
                if (store.settings.value.selection == null) models.firstOrNull()?.let { store.select(id, it.id) }
                onDone()
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("agent_provider_save"),
        ) { Text("Save") }
    }
}

@Composable
private fun ModelRow(model: ModelConfig, onChange: (ModelConfig) -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
                Text(model.name + if (model.name != model.id) " (${model.id})" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove model") }
            }
            if (open) {
                TextSetting("Context length", model.contextLength.toString(), KeyboardType.Number) { v -> v.toIntOrNull()?.let { onChange(model.copy(contextLength = it)) } }
                TextSetting("Max output tokens", model.maxOutputTokens?.toString().orEmpty(), KeyboardType.Number) { v -> onChange(model.copy(maxOutputTokens = v.toIntOrNull())) }
                TextSetting("Temperature", model.temperature?.toString().orEmpty(), KeyboardType.Decimal) { v -> onChange(model.copy(temperature = v.toDoubleOrNull())) }
                SwitchRow("Supports tool calling", model.supportsTools) { onChange(model.copy(supportsTools = it)) }
            }
        }
    }
}

@Composable
private fun SecretField(
    label: String,
    isSet: Boolean,
    multiline: Boolean = false,
    testTag: String = "agent_secret",
    onSave: ((String?) -> Unit)?,
    onChange: (String) -> Unit = {},
) {
    var value by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; onChange(it) },
            label = { Text(label + if (isSet) " (saved — type to replace)" else "") },
            visualTransformation = if (multiline) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = !multiline,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f).testTag(testTag),
        )
        if (onSave != null) {
            IconButton(onClick = { onSave(value.ifBlank { null }); value = "" }) { Icon(Icons.Rounded.Check, "Save $label") }
        }
    }
}

@Composable
private fun TextSetting(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

private fun parsePairs(text: String, separator: Char): Map<String, String> =
    text.lines().mapNotNull { line ->
        val index = line.indexOf(separator)
        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()
