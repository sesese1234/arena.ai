package com.nuvio.app.agent

import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.tools.AgentToolCoverage
import com.nuvio.app.agent.tools.agentSettings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentToolCatalogTest {
    private val registry = fullCatalog()

    @Test
    fun everyToolHasValidNameDescriptionAndDocumentedParams() {
        assertTrue(registry.all.size >= 70, "expected a full catalog, got ${registry.all.size}")
        registry.all.forEach { tool ->
            assertTrue(tool.description.length >= 15, "${tool.name}: description too short")
            val properties = tool.schema["properties"]!!.jsonObject
            properties.forEach { (param, schema) ->
                assertTrue(schema.jsonObject["description"]!!.jsonPrimitive.content.isNotBlank(), "${tool.name}.$param has no description")
            }
            tool.params.filter { it.required }.forEach { assertTrue(it.name in properties, "${tool.name}: required ${it.name} missing") }
        }
    }

    @Test
    fun destructiveAndAccountActionsRequireConfirmation() {
        val mustConfirm = listOf(
            "remove_addon", "remove_from_library", "clear_library", "sign_out", "switch_profile",
            "delete_collection", "reset_home_rows", "reset_details_layout", "remove_from_continue_watching",
        )
        mustConfirm.forEach { name ->
            val tool = registry[name] ?: fail("missing tool $name")
            assertTrue(tool.requiresConfirmation, "$name must ask the user first")
        }
    }

    @Test
    fun requiredCapabilitiesArePresent() {
        val names = registry.all.map { it.name }.toSet()
        val expected = listOf(
            // read
            "get_app_state", "get_visible_items", "get_player_state", "list_addons", "get_library", "list_settings", "get_continue_watching",
            // navigation
            "open_tab", "go_back", "open_title", "open_settings", "open_catalog",
            // player
            "play_title", "list_streams", "play_stream", "player_pause", "player_resume", "player_seek",
            "player_select_subtitles", "player_select_audio", "player_set_speed", "player_next_episode",
            // built-ins
            "web_search", "fetch_url", "calculate", "get_current_time", "remember", "recall",
            "addon_manifest", "addon_catalog", "addon_meta", "addon_streams", "addon_subtitles",
        )
        val missing = expected.filterNot(names::contains)
        assertTrue(missing.isEmpty(), "missing tools: $missing")
        assertTrue(ToolCategory.entries.all { category -> registry.all.any { it.category == category } }, "every category has tools")
    }

    @Test
    fun settingsTableHasUniqueKeysAndReadableDescriptions() {
        val keys = agentSettings.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate setting keys")
        agentSettings.forEach { assertTrue(it.description.isNotBlank() && it.covers.contains('.'), it.key) }
    }

    /**
     * Fails when a public action on a covered repository/bridge has neither a tool nor a documented
     * exemption in [AgentToolCoverage.EXEMPTIONS]. This is what keeps the tool registry exhaustive.
     */
    @Test
    fun everyPublicAppActionHasAToolOrADocumentedExemption() {
        val covered = registry.all.flatMap { it.covers }.toSet()
        val missing = mutableListOf<String>()
        AgentToolCoverage.OWNERS.forEach { className ->
            val clazz = Class.forName(className, false, javaClass.classLoader)
            val owner = clazz.simpleName
            publicMembers(clazz).forEach { member ->
                val key = "$owner.$member"
                if (key !in covered && key !in AgentToolCoverage.EXEMPTIONS) missing += key
            }
        }
        if (missing.isNotEmpty()) {
            fail(
                "Public app actions without an agent tool (add covers(\"…\") to a tool or document an exemption " +
                    "in AgentToolCoverage.EXEMPTIONS):\n" + missing.sorted().joinToString("\n"),
            )
        }
    }

    @Test
    fun exemptionsAndCoversPointAtRealMembers() {
        val real = AgentToolCoverage.OWNERS.flatMap { name ->
            val clazz = Class.forName(name, false, javaClass.classLoader)
            publicMembers(clazz).map { "${clazz.simpleName}.$it" }
        }.toSet()
        val stale = AgentToolCoverage.EXEMPTIONS.keys.filterNot(real::contains)
        assertTrue(stale.isEmpty(), "Exemptions for members that no longer exist: $stale")
        val ownerNames = AgentToolCoverage.OWNERS.map { it.substringAfterLast('.') }.toSet()
        val bogusCovers = registry.all.flatMap { tool -> tool.covers.map { tool.name to it } }
            .filter { (_, member) -> member.substringBefore('.') in ownerNames && member !in real }
        assertTrue(bogusCovers.isEmpty(), "covers() naming non-existent members: $bogusCovers")
    }

    private fun publicMembers(clazz: Class<*>): Set<String> =
        clazz.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .filterNot { '$' in it.name || it.name in IGNORED }
            .map { method ->
                // Kotlin mangles names of functions returning inline classes (Result): signOut-IoAF18A.
                val name = method.name.substringBefore('-')
                val isGetter = method.parameterCount == 0 && name.startsWith("get") && name.length > 3 && name[3].isUpperCase()
                if (isGetter) name[3].lowercase() + name.substring(4) else name
            }
            .toSet()

    private companion object {
        val IGNORED = setOf("equals", "hashCode", "toString")
    }
}
