package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider

/** Compile a real independent plugin; no fixture implementation is on the host classpath. */
internal fun pluginJar(
    directory: Path, version: String = "one", id: String = "probe", api: String = "1",
    category: String = "NOTES", schemaType: String = "object", localName: String = "version",
    initialize: String = "",
    handler: String? = null,
): Path {
    Files.createDirectories(directory)
    val build = Files.createTempDirectory("souz-plugin-fixture")
    try {
        val source = build.resolve("Fixture.java")
        Files.writeString(source, """
            package fixture;
            import java.util.*;
            import ru.souz.plugins.*;
            import ru.souz.llms.*;
            public final class Fixture implements SouzPlugin {
                public List<PluginTool> getTools() {
                    $initialize
                    var function = new LLMRequest.Function("$localName", "$version",
                        new LLMRequest.Parameters("$schemaType", Map.of(), List.of(), false), null, null);
                    return List.of(new PluginTool(function, "$category", (args, context, continuation) ->
                        ${handler ?: "LLMToolSetupKt.getRestJsonMapper().createObjectNode().put(\"version\", \"$version\")"}));
                }
            }
        """.trimIndent())
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val classpath = listOf(ru.souz.plugins.SouzPlugin::class.java, ru.souz.llms.LLMRequest::class.java,
            com.fasterxml.jackson.databind.JsonNode::class.java, com.fasterxml.jackson.core.TreeNode::class.java,
            kotlin.Unit::class.java).map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        check(compiler.run(null, null, null, "-classpath", classpath.joinToString(java.io.File.pathSeparator), "-d", build.toString(), source.toString()) == 0)
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Souz-Plugin-Id", id)
            mainAttributes.putValue("Souz-Plugin-Version", version)
            mainAttributes.putValue("Souz-Plugin-Api", api)
            mainAttributes.putValue("Souz-Plugin-Class", "fixture.Fixture")
        }
        val jar = directory.resolve("$id.jar")
        val staged = directory.resolve("$id.jar.part")
        JarOutputStream(Files.newOutputStream(staged), manifest).use { output ->
            output.putNextEntry(JarEntry("fixture/Fixture.class"))
            Files.copy(build.resolve("fixture/Fixture.class"), output)
            output.closeEntry()
            output.putNextEntry(JarEntry("plugin-marker"))
            output.write(version.toByteArray())
            output.closeEntry()
        }
        Files.move(staged, jar, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return jar
    } finally { build.toFile().deleteRecursively() }
}
