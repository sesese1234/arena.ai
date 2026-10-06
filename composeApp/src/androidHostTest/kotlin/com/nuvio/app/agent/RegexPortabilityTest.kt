package com.nuvio.app.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Android compiles regexes with ICU, which rejects a literal `}` that isn't escaped; the JVM used
 * by these tests accepts it. An unescaped `}` in `${NAME}` placeholder matching broke every agent
 * request on a real emulator while all JVM tests passed, so this scans the agent sources for regex
 * literals with a bare `}` that isn't closing a `{n,m}` quantifier.
 */
class RegexPortabilityTest {
    private val regexLiteral = Regex("""(?:Regex|toRegex|Pattern\.compile)\(\s*"((?:[^"\\]|\\.)*)"""")
    private val quantifier = Regex("""(?<!\\)\{\d+(,\d*)?\}""")

    @Test
    fun agentRegexLiteralsAreIcuSafe() {
        val root = listOf("src", "composeApp/src").map(::File).firstOrNull { it.isDirectory }
            ?: fail("cannot find composeApp sources from ${File(".").absolutePath}")
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/agent/" in it.invariantSeparatorsPath && "Test" !in it.name }
            .toList()
        assertTrue(sources.isNotEmpty(), "no agent sources found under ${root.absolutePath}")
        val offenders = sources.flatMap { file ->
            regexLiteral.findAll(file.readText()).mapNotNull { match ->
                // Kotlin source escapes: "\\}" in source is \} in the pattern.
                val pattern = match.groupValues[1].replace("\\\\", "\\")
                val withoutQuantifiers = quantifier.replace(pattern, "")
                val bare = Regex("""(?<!\\)\}""").containsMatchIn(withoutQuantifiers)
                if (bare) "${file.name}: ${match.groupValues[1]}" else null
            }.toList()
        }
        assertTrue(offenders.isEmpty(), "Regex literals with an unescaped '}' fail on Android (ICU):\n" + offenders.joinToString("\n"))
    }
}
