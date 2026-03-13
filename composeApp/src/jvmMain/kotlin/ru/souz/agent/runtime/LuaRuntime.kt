package ru.souz.agent.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import ru.souz.agent.engine.AgentSettings
import ru.souz.giga.GigaRequest
import ru.souz.giga.GigaResponse
import ru.souz.giga.gigaJsonMapper
import kotlin.coroutines.CoroutineContext

class LuaRuntime(
    private val toolExecutor: AgentToolExecutor,
) {
    suspend fun execute(
        code: String,
        settings: AgentSettings,
        activeTools: List<GigaRequest.Function>,
    ): String = withContext(Dispatchers.IO) {
        try {
            val globals = createGlobals(
                settings = settings,
                activeTools = activeTools.associateBy { it.name },
                parentContext = currentCoroutineContext(),
            )
            val chunk = globals.load(code, "agent.lua")
            val result = chunk.invoke().arg1().takeUnless { it.isnil() } ?: globals.get("result")
            luaResultToString(result)
        } catch (e: LuaError) {
            throw LuaExecutionException(message = e.message ?: "Lua execution failed", code, e)
        }
    }

    private fun createGlobals(
        settings: AgentSettings,
        activeTools: Map<String, GigaRequest.Function>,
        parentContext: CoroutineContext,
    ): Globals {
        val globals = JsePlatform.standardGlobals()
        forbidden.forEach { globals.set(it, LuaValue.NIL) }

        val toolsTable = LuaTable()
        activeTools.forEach { (name, fn) ->
            val callable = createToolFunction(
                toolName = name,
                function = fn,
                settings = settings,
                parentContext = parentContext,
            )
            toolsTable.set(name, callable)
            if (isLuaIdentifier(name)) {
                globals.set(name, callable)
            }
        }

        globals.set("tools", toolsTable)
        globals.set("call_tool", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val toolName = args.arg(1).checkjstring()
                val toolFunction = toolsTable.get(toolName)
                require(!toolFunction.isnil()) { "Unknown tool: $toolName" }
                return toolFunction.invoke(sliceArguments(args, fromIndex = 2))
            }
        })
        globals.set("json_decode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = javaToLua(
                gigaJsonMapper.readValue(arg.checkjstring(), Any::class.java)
            )
        })
        globals.set("json_encode", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = valueOf(
                gigaJsonMapper.writeValueAsString(luaToJava(arg))
            )
        })
        return globals
    }

    private fun createToolFunction(
        toolName: String,
        function: GigaRequest.Function,
        settings: AgentSettings,
        parentContext: CoroutineContext,
    ): VarArgFunction = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val arguments = argsToToolArguments(args, function)
            val functionCall = GigaResponse.FunctionCall(
                name = toolName,
                arguments = arguments,
            )
            val toolMessage = runBlocking(parentContext) {
                toolExecutor.execute(settings, functionCall)
            }
            val decoded = decodeToolResult(toolMessage.content)
            return javaToLua(decoded)
        }
    }

    private fun argsToToolArguments(
        args: Varargs,
        function: GigaRequest.Function,
    ): Map<String, Any> {
        if (args.narg() == 0 || args.arg1().isnil()) return emptyMap()

        val firstArg = args.arg1()
        if (firstArg.istable()) {
            val javaValue = luaToJava(firstArg)
            require(javaValue is Map<*, *>) { "Tool arguments must be a Lua table" }
            return javaValue.entries
                .mapNotNull { (key, value) ->
                    val stringKey = key?.toString() ?: return@mapNotNull null
                    val safeValue = value ?: return@mapNotNull null
                    stringKey to safeValue
                }
                .toMap()
        }

        val propertyNames = function.parameters.properties.keys.toList()
        require(propertyNames.size == 1) {
            "Tool ${function.name} expects one Lua table argument with named parameters"
        }
        val scalarValue = luaToJava(firstArg)
        require(scalarValue != null) { "Nil is not supported as a scalar tool argument" }
        return mapOf(propertyNames.first() to scalarValue)
    }

    private fun decodeToolResult(rawContent: String): Any? = runCatching {
        gigaJsonMapper.readValue(rawContent, Any::class.java)
    }.getOrElse { rawContent }

    private fun luaResultToString(value: LuaValue): String = when {
        value.isnil() -> ""
        value.isstring() || value.isnumber() || value.isboolean() -> value.tojstring()
        else -> gigaJsonMapper.writeValueAsString(luaToJava(value))
    }

    private fun luaToJava(value: LuaValue): Any? = when {
        value.isnil() -> null
        value.isboolean() -> value.toboolean()
        value.isnumber() -> numberToJava(value)
        value.isstring() -> value.tojstring()
        value.istable() -> tableToJava(value.checktable())
        else -> value.tojstring()
    }

    private fun numberToJava(value: LuaValue): Any {
        val doubleValue = value.todouble()
        val longValue = value.tolong()
        return if (doubleValue == longValue.toDouble()) {
            when {
                longValue in Int.MIN_VALUE..Int.MAX_VALUE -> longValue.toInt()
                else -> longValue
            }
        } else {
            doubleValue
        }
    }

    private fun tableToJava(table: LuaTable): Any {
        val entries = ArrayList<Pair<Any, Any?>>()
        var key = LuaValue.NIL
        while (true) {
            val next = table.next(key)
            val nextKey = next.arg1()
            if (nextKey.isnil()) break
            entries += luaTableKeyToJava(nextKey) to luaToJava(next.arg(2))
            key = nextKey
        }

        if (entries.isEmpty()) return emptyMap<String, Any>()

        val intKeys = entries.mapNotNull { (keyValue, _) -> keyValue as? Int }
        val isArray = intKeys.size == entries.size &&
                intKeys.sorted() == (1..entries.size).toList()

        return if (isArray) {
            entries.sortedBy { it.first as Int }.map { it.second }
        } else {
            LinkedHashMap<String, Any?>().apply {
                entries.forEach { (entryKey, entryValue) ->
                    put(entryKey.toString(), entryValue)
                }
            }
        }
    }

    private fun luaTableKeyToJava(key: LuaValue): Any = when {
        key.isnumber() -> key.toint()
        else -> key.tojstring()
    }

    private fun javaToLua(value: Any?): LuaValue = when (value) {
        null -> LuaValue.NIL
        is LuaValue -> value
        is String -> LuaValue.valueOf(value)
        is Boolean -> LuaValue.valueOf(value)
        is Int -> LuaValue.valueOf(value)
        is Long -> LuaValue.valueOf(value.toDouble())
        is Float -> LuaValue.valueOf(value.toDouble())
        is Double -> LuaValue.valueOf(value)
        is Map<*, *> -> LuaTable().also { table ->
            value.forEach { (key, mapValue) ->
                if (key != null) {
                    table.set(key.toString(), javaToLua(mapValue))
                }
            }
        }

        is Iterable<*> -> LuaTable().also { table ->
            value.forEachIndexed { index, item ->
                table.set(index + 1, javaToLua(item))
            }
        }

        is Array<*> -> LuaTable().also { table ->
            value.forEachIndexed { index, item ->
                table.set(index + 1, javaToLua(item))
            }
        }

        else -> LuaValue.valueOf(value.toString())
    }

    private fun isLuaIdentifier(name: String): Boolean =
        name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))

    private fun sliceArguments(args: Varargs, fromIndex: Int): Varargs {
        if (fromIndex > args.narg()) return LuaValue.NONE
        val values = Array(args.narg() - fromIndex + 1) { offset -> args.arg(fromIndex + offset) }
        return LuaValue.varargsOf(values)
    }

    companion object {
        val forbidden = listOf("dofile", "loadfile", "require", "package", "io", "os", "debug", "luajava")
    }
}

class LuaExecutionException(
    message: String,
    val code: String,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(message, code), cause)

private fun buildMessage(message: String, code: String): String {
    val preview = code.lineSequence()
        .take(20)
        .joinToString("\n")
        .ifBlank { "<empty lua code>" }
    return "$message\n--- lua code preview ---\n$preview"
}
