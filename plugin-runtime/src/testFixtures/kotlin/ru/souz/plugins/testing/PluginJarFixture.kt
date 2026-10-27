package ru.souz.plugins.testing

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.tools.ToolProvider
import ru.souz.plugins.SouzPlugin

/** Compiles a real artifact whose entry point is absent from the test host's classpath. */
fun pluginJar(
    path: Path,
    id: String = "fixture",
    version: String = "1",
    api: String = "1",
    startBody: String = "return kotlin.Unit.INSTANCE;",
    stopBody: String = "return kotlin.Unit.INSTANCE;",
    duplicateTools: Boolean = false,
    toolName: String = "version",
    schemaBody: String = "",
    invokeBody: String = """return JsonNodeFactory.instance.objectNode().put("version", "$version");""",
): Path {
    val root = Files.createTempDirectory(path.parent, "compiler-")
    val source = root.resolve("FixturePlugin.java")
    Files.writeString(source, """
        package generated;
        import ru.souz.plugins.*;
        import com.fasterxml.jackson.databind.*;
        import com.fasterxml.jackson.databind.node.*;
        import kotlin.coroutines.Continuation;
        public class FixturePlugin implements SouzPlugin {
            public Object start(Continuation<? super kotlin.Unit> c) { $startBody }
            public Object stop(Continuation<? super kotlin.Unit> c) { $stopBody }
            public java.util.List<PluginTool> tools() {
                PluginTool tool = new PluginTool() {
                    public PluginToolDefinition getDefinition() {
                        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
                        $schemaBody
                        return new PluginToolDefinition("$toolName", "Report generation $version", schema, "NOTES");
                    }
                    public Object invoke(ObjectNode arguments, PluginCallContext context, Continuation<? super JsonNode> c) {
                        $invokeBody
                    }
                };
                return java.util.List.of(${if (duplicateTools) "tool, tool" else "tool"});
            }
        }
    """.trimIndent())
    val classpath = listOf(PluginLifecycleProbe::class.java, SouzPlugin::class.java, JsonNode::class.java, JsonToken::class.java, Unit::class.java)
        .joinToString(java.io.File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
    check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath, "-d", root.toString(), source.toString()) == 0)
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes.putValue("Souz-Plugin-Id", id)
        mainAttributes.putValue("Souz-Plugin-Version", version)
        mainAttributes.putValue("Souz-Plugin-Api", api)
        mainAttributes.putValue("Souz-Plugin-Class", "generated.FixturePlugin")
    }
    val temporary = Files.createTempFile(path.parent, "publish-", ".part")
    JarOutputStream(Files.newOutputStream(temporary), manifest).use { jar ->
        Files.walk(root).use { files ->
            files.filter { it.toString().endsWith(".class") }.sorted().forEach { file ->
                jar.putNextEntry(JarEntry(root.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, jar)
                jar.closeEntry()
            }
        }
    }
    Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    return path
}

/** Parent-loaded test boundary for deterministic coordination with independently loaded fixtures. */
object PluginLifecycleProbe {
    var onStart: suspend () -> Unit = {}
    var onStop: suspend () -> Unit = {}
    @JvmStatic suspend fun start() = onStart()
    @JvmStatic suspend fun stop() = onStop()
}
