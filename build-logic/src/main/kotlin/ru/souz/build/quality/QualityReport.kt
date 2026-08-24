package ru.souz.build.quality

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

internal object QualityReport {
    private val mapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun write(
        results: List<QualityCheckResult>,
        repositoryDirectory: File,
        jsonFile: File,
        markdownFile: File,
    ): QualityStatus {
        val sortedResults = results.sortedBy { it.definition.id }
        require(sortedResults.isNotEmpty()) { "A quality report needs at least one check result." }
        require(sortedResults.map { it.definition.lane }.distinct().size == 1) {
            "A quality report cannot mix execution lanes."
        }
        require(sortedResults.map { it.definition.authority }.distinct().size == 1) {
            "A quality report cannot mix authorities."
        }
        require(sortedResults.all { result ->
            result.diagnostics.all { diagnostic -> diagnostic.path == null || !File(diagnostic.path).isAbsolute }
        }) { "Quality diagnostics must use repository-relative paths." }

        val status = aggregateStatus(sortedResults)
        val identity = sortedResults.first().gitIdentity
        val lane = sortedResults.first().definition.lane
        val authority = sortedResults.first().definition.authority
        val evidencePath = jsonFile.relativeInvariantPath(repositoryDirectory)
        val checks = mapper.createArrayNode()
        sortedResults.forEachIndexed { index, result ->
            val normalizedHash = sha256(normalizedCheckNode(result))
            checks.add(checkNode(result, "$evidencePath#/checks/$index", normalizedHash))
        }

        val report = reportHeader(status, identity, lane, authority)
        report.put("normalizedSha256", sha256(normalizedReportNode(status, sortedResults)))
        report.set<ArrayNode>("checks", checks)

        Files.createDirectories(jsonFile.toPath().parent)
        Files.createDirectories(markdownFile.toPath().parent)
        Files.writeString(
            jsonFile.toPath(),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
            StandardCharsets.UTF_8,
        )
        Files.writeString(markdownFile.toPath(), markdown(status, sortedResults, report), StandardCharsets.UTF_8)
        return status
    }

    fun normalizedHash(result: QualityCheckResult): String = sha256(normalizedCheckNode(result))

    private fun aggregateStatus(results: List<QualityCheckResult>): QualityStatus = when {
        results.any { it.status == QualityStatus.ERROR } -> QualityStatus.ERROR
        results.any { it.status == QualityStatus.FAIL && it.definition.enforcement == QualityEnforcement.BLOCKING } ->
            QualityStatus.FAIL
        results.any { it.status == QualityStatus.WARNING } -> QualityStatus.WARNING
        results.all { it.status == QualityStatus.SKIPPED } -> QualityStatus.SKIPPED
        results.any { it.status == QualityStatus.NOT_AUTHORITATIVE } -> QualityStatus.NOT_AUTHORITATIVE
        else -> QualityStatus.PASS
    }

    private fun reportHeader(
        status: QualityStatus,
        identity: GitIdentity,
        lane: QualityLane,
        authority: QualityAuthority,
    ): ObjectNode = mapper.createObjectNode().apply {
        put("schemaVersion", 1)
        put("lane", lane.wireName)
        put("status", status.wireName)
        put("authority", authority.wireName)
        putNullable("testedCommitSha", identity.testedCommitSha)
        putNullable("prBaseSha", identity.prBaseSha)
        putNullable("prHeadSha", identity.prHeadSha)
        putNullable("dirtyWorktree", identity.dirtyWorktree)
    }

    private fun normalizedReportNode(
        status: QualityStatus,
        results: List<QualityCheckResult>,
    ): ObjectNode = reportHeader(
        status,
        results.first().gitIdentity,
        results.first().definition.lane,
        results.first().definition.authority,
    ).apply {
        set<ArrayNode>("checks", mapper.createArrayNode().apply {
            results.forEach { add(normalizedCheckNode(it)) }
        })
    }

    private fun checkNode(
        result: QualityCheckResult,
        evidencePath: String,
        normalizedHash: String,
    ): ObjectNode = normalizedCheckNode(result).apply {
        put("durationMs", result.durationMs)
        set<ArrayNode>("evidence", mapper.createArrayNode().apply {
            add(mapper.createObjectNode().apply {
                put("path", evidencePath)
                put("normalizedSha256", normalizedHash)
            })
        })
    }

    private fun normalizedCheckNode(result: QualityCheckResult): ObjectNode = mapper.createObjectNode().apply {
        put("id", result.definition.id)
        put("implementationVersion", result.definition.implementationVersion)
        put("description", result.definition.description)
        put("policy", result.definition.policy)
        put("lane", result.definition.lane.wireName)
        put("authority", result.definition.authority.wireName)
        put("enforcement", result.definition.enforcement.wireName)
        put("status", result.status.wireName)
        putNullable("testedCommitSha", result.gitIdentity.testedCommitSha)
        putNullable("prBaseSha", result.gitIdentity.prBaseSha)
        putNullable("prHeadSha", result.gitIdentity.prHeadSha)
        putNullable("dirtyWorktree", result.gitIdentity.dirtyWorktree)
        set<ArrayNode>("diagnostics", mapper.createArrayNode().apply {
            result.diagnostics.forEach { diagnostic ->
                add(mapper.createObjectNode().apply {
                    putNullable("path", diagnostic.path)
                    putNullable("line", diagnostic.line)
                    put("message", diagnostic.message)
                })
            }
        })
    }

    private fun markdown(
        status: QualityStatus,
        results: List<QualityCheckResult>,
        report: ObjectNode,
    ): String = buildString {
        val identity = results.first().gitIdentity
        val lane = results.first().definition.lane
        val authority = results.first().definition.authority
        appendLine("# Souz quality gate: ${lane.wireName}")
        appendLine()
        appendLine("Status: **${status.wireName.uppercase()}**")
        appendLine()
        appendLine("- Authority: `${authority.wireName}`")
        appendLine("- Tested commit: `${identity.testedCommitSha ?: "unknown"}`")
        appendLine("- PR base: `${identity.prBaseSha ?: "not provided"}`")
        appendLine("- PR head: `${identity.prHeadSha ?: "not provided"}`")
        appendLine("- Worktree: `${identity.dirtyWorktree?.let { if (it) "dirty" else "clean" } ?: "unknown"}`")
        appendLine("- Normalized evidence: `${report.path("normalizedSha256").asText()}`")
        appendLine()
        appendLine("| Check | Status | Enforcement | Duration | Policy |")
        appendLine("| --- | --- | --- | ---: | --- |")
        results.forEach { result ->
            appendLine(
                "| `${tableCell(result.definition.id)}` | `${result.status.wireName}` | " +
                    "`${result.definition.enforcement.wireName}` | ${result.durationMs} ms | " +
                    "`${tableCell(result.definition.policy)}` |"
            )
        }
        appendLine()
        appendLine("## Diagnostics")
        appendLine()
        if (results.all { it.diagnostics.isEmpty() }) {
            appendLine("No diagnostics.")
        } else {
            results.filter { it.diagnostics.isNotEmpty() }.forEach { result ->
                appendLine("### `${result.definition.id}`")
                appendLine()
                result.diagnostics.forEach { diagnostic ->
                    val location = diagnostic.path?.let { path ->
                        if (diagnostic.line == null) path else "$path:${diagnostic.line}"
                    } ?: "gate"
                    appendLine("- `$location`: ${diagnostic.message}")
                }
                appendLine()
            }
        }
    }

    private fun sha256(node: ObjectNode): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(node))
        return "sha256:" + digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun tableCell(value: String): String = value.replace("|", "\\|")

    private fun ObjectNode.putNullable(name: String, value: String?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private fun ObjectNode.putNullable(name: String, value: Boolean?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private fun ObjectNode.putNullable(name: String, value: Int?) {
        if (value == null) putNull(name) else put(name, value)
    }
}
