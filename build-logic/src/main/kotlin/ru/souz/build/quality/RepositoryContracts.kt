package ru.souz.build.quality

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

internal object RepositoryContracts {
    private val parser = Parser.builder()
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()
    private val modulePathPattern = Regex(":[A-Za-z0-9][A-Za-z0-9_-]*(?::[A-Za-z0-9][A-Za-z0-9_-]*)*")
    private val externalSchemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun check(
        repositoryDirectory: File,
        projects: List<ProjectDescriptor>,
        policyFiles: Set<File>,
        registeredChecks: List<QualityCheckDefinition>,
    ): List<QualityDiagnostic> {
        val repository = repositoryDirectory.toPath().toAbsolutePath().normalize()
        val diagnostics = mutableListOf<QualityDiagnostic>()
        val rootAgents = repository.resolve("AGENTS.md")

        if (Files.notExists(rootAgents)) {
            diagnostics += missingFile("AGENTS.md", "Root policy file is missing.")
        }

        val expectedPolicyPaths = buildSet {
            add(rootAgents)
            projects.forEach { project ->
                val moduleDirectory = repository.resolve(project.directory).normalize()
                add(moduleDirectory.resolve("AGENTS.md"))
            }
            policyFiles.forEach { add(it.toPath().toAbsolutePath().normalize()) }
        }
        val documents = expectedPolicyPaths
            .filter { Files.exists(it) }
            .sortedBy { it.toString() }
            .associateWith { path -> parser.parse(Files.readString(path, StandardCharsets.UTF_8)) }

        val rootDocument = documents[rootAgents]
        val moduleMap = rootDocument?.let { sectionUnderHeading(it, "Module Map") }
        val exemptions = rootDocument?.let { sectionUnderHeading(it, "Module Policy Exemptions") }
            ?: MarkdownSection(found = false, headingLine = null, mentions = emptyList())
        val exemptionPaths = exemptions.mentions.map(QualityModuleMention::path).toSet()

        if (rootDocument != null) {
            checkModuleMap(projects, moduleMap!!, diagnostics)
            checkExemptions(projects, exemptions, documents, repository, diagnostics)
        }

        checkModulePolicies(
            repository = repository,
            projects = projects,
            exemptions = exemptionPaths,
            documents = documents,
            diagnostics = diagnostics,
        )
        checkRegisteredPolicies(repository, registeredChecks, diagnostics)
        checkLocalLinks(repository, documents, diagnostics)

        return diagnostics
    }

    private fun checkModuleMap(
        projects: List<ProjectDescriptor>,
        section: MarkdownSection,
        diagnostics: MutableList<QualityDiagnostic>,
    ) {
        if (!section.found) {
            diagnostics += QualityDiagnostic(
                path = "AGENTS.md",
                line = null,
                message = "Root policy must contain a 'Module Map' level-two heading.",
            )
        }

        section.mentions.groupBy(QualityModuleMention::path)
            .filterValues { it.size > 1 }
            .forEach { (module, mentions) ->
                diagnostics += QualityDiagnostic(
                    path = "AGENTS.md",
                    line = mentions.drop(1).firstOrNull()?.line,
                    message = "$module appears more than once in the root Module Map.",
                )
            }

        val documented = section.mentions.map(QualityModuleMention::path).toSet()
        val actual = projects.map(ProjectDescriptor::path).toSet()
        (actual - documented).sorted().forEach { module ->
            diagnostics += QualityDiagnostic(
                path = "AGENTS.md",
                line = section.headingLine,
                message = "$module is included by Gradle but missing from the root Module Map.",
            )
        }
        (documented - actual).sorted().forEach { module ->
            diagnostics += QualityDiagnostic(
                path = "AGENTS.md",
                line = section.mentions.firstOrNull { it.path == module }?.line,
                message = "$module is listed in the root Module Map but is not a Gradle project.",
            )
        }
    }

    private fun checkExemptions(
        projects: List<ProjectDescriptor>,
        exemptions: MarkdownSection,
        documents: Map<Path, Node>,
        repository: Path,
        diagnostics: MutableList<QualityDiagnostic>,
    ) {
        val actual = projects.map(ProjectDescriptor::path).toSet()
        exemptions.mentions.forEach { exemption ->
            val descriptor = projects.firstOrNull { it.path == exemption.path }
            when {
                exemption.path !in actual -> diagnostics += QualityDiagnostic(
                    path = "AGENTS.md",
                    line = exemption.line,
                    message = "${exemption.path} is exempted from module policy but is not a Gradle project.",
                )

                descriptor != null && documents.containsKey(
                    repository.resolve(descriptor.directory).resolve("AGENTS.md").normalize()
                ) -> diagnostics += QualityDiagnostic(
                    path = "AGENTS.md",
                    line = exemption.line,
                    message = "${exemption.path} has an AGENTS.md policy, so its exemption is stale.",
                )
            }
        }
    }

    private fun checkModulePolicies(
        repository: Path,
        projects: List<ProjectDescriptor>,
        exemptions: Set<String>,
        documents: Map<Path, Node>,
        diagnostics: MutableList<QualityDiagnostic>,
    ) {
        projects.forEach { project ->
            val moduleDirectory = repository.resolve(project.directory).normalize()
            val agentsPath = moduleDirectory.resolve("AGENTS.md")
            val agentsRelative = relativePath(repository, agentsPath)
            val document = documents[agentsPath]

            if (document == null && project.path !in exemptions) {
                diagnostics += missingFile(
                    agentsRelative,
                    "${project.path} needs an AGENTS.md policy or an explicit root policy exemption.",
                )
            } else if (document != null) {
                val section = sectionUnderHeading(document, "Pain points")
                if (!section.found) {
                    diagnostics += QualityDiagnostic(
                        path = agentsRelative,
                        line = null,
                        message = "${project.path} AGENTS.md must contain a 'Pain points' level-two heading.",
                    )
                } else {
                    val targets = section.links.mapNotNull { link ->
                        runCatching { resolveLocalTarget(repository, agentsPath, link.destination) }.getOrNull()
                    }.toSet()
                    documents.keys.filter { it.parent == moduleDirectory.resolve("docs") && it !in targets }
                        .forEach { topic ->
                            diagnostics += QualityDiagnostic(
                                path = agentsRelative,
                                line = section.headingLine,
                                message = "${relativePath(repository, topic)} must be linked from this 'Pain points' section.",
                            )
                        }
                }
            }
        }
    }

    private fun checkRegisteredPolicies(
        repository: Path,
        registeredChecks: List<QualityCheckDefinition>,
        diagnostics: MutableList<QualityDiagnostic>,
    ) {
        registeredChecks.forEach { check ->
            val policyPath = check.policy.substringBefore('#')
            val target = repository.resolve(policyPath).normalize()
            if (!target.startsWith(repository) || Files.notExists(target)) {
                diagnostics += QualityDiagnostic(
                    path = policyPath,
                    line = null,
                    message = "Registered check '${check.id}' references a missing policy location.",
                )
            }
        }
    }

    private fun checkLocalLinks(
        repository: Path,
        documents: Map<Path, Node>,
        diagnostics: MutableList<QualityDiagnostic>,
    ) {
        documents.toSortedMap(compareBy { it.toString() }).forEach { (source, document) ->
            links(document).forEach { link ->
                val target = try {
                    resolveLocalTarget(repository, source, link.destination)
                } catch (_: IllegalArgumentException) {
                    diagnostics += QualityDiagnostic(
                        path = relativePath(repository, source),
                        line = link.line,
                        message = "Repository-local link is invalid or escapes the repository.",
                    )
                    null
                }
                if (target != null && Files.notExists(target)) {
                    diagnostics += QualityDiagnostic(
                        path = relativePath(repository, source),
                        line = link.line,
                        message = "Repository-local link does not resolve.",
                    )
                }
            }
        }
    }

    private fun resolveLocalTarget(repository: Path, source: Path, destination: String): Path? {
        val trimmed = destination.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/")) {
            return null
        }
        if (externalSchemePattern.containsMatchIn(trimmed)) {
            return null
        }

        val encodedPath = trimmed.substringBefore('#').substringBefore('?')
        val decodedPath = URLDecoder.decode(encodedPath.replace("+", "%2B"), StandardCharsets.UTF_8)
        val target = if (decodedPath.isEmpty()) source else source.parent.resolve(decodedPath).normalize()
        require(target.startsWith(repository)) { "Link escapes repository." }
        return target
    }

    private fun links(document: Node): List<MarkdownLink> {
        val links = mutableListOf<MarkdownLink>()
        document.accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                links += MarkdownLink(link.destination, lineOf(link))
                visitChildren(link)
            }

            override fun visit(image: Image) {
                links += MarkdownLink(image.destination, lineOf(image))
                visitChildren(image)
            }
        })
        return links
    }

    private fun sectionUnderHeading(document: Node, title: String): MarkdownSection {
        val mentions = mutableListOf<QualityModuleMention>()
        val links = mutableListOf<MarkdownLink>()
        var inSection = false
        var found = false
        var headingLine: Int? = null

        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                if (heading.level <= 2) {
                    inSection = heading.level == 2 && textOf(heading).trim().equals(title, ignoreCase = true)
                    if (inSection) {
                        found = true
                        headingLine = lineOf(heading)
                    }
                }
                visitChildren(heading)
            }

            override fun visit(listItem: ListItem) {
                if (inSection) {
                    var firstModule: Code? = null
                    listItem.accept(object : AbstractVisitor() {
                        override fun visit(code: Code) {
                            if (firstModule == null && code.literal.matches(modulePathPattern)) {
                                firstModule = code
                            }
                        }
                    })
                    firstModule?.let { code ->
                        mentions += QualityModuleMention(code.literal, lineOf(code))
                    }
                }
                visitChildren(listItem)
            }

            override fun visit(link: Link) {
                if (inSection) {
                    links += MarkdownLink(link.destination, lineOf(link))
                }
                visitChildren(link)
            }
        })

        return MarkdownSection(found, headingLine, mentions, links)
    }

    private fun textOf(node: Node): String = buildString {
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                append(text.literal)
            }

            override fun visit(code: Code) {
                append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                append(' ')
            }
        })
    }

    private fun lineOf(node: Node): Int? = node.sourceSpans.firstOrNull()?.lineIndex?.plus(1)

    private fun missingFile(path: String, message: String) = QualityDiagnostic(path, null, message)

    private fun relativePath(repository: Path, path: Path): String =
        repository.relativize(path.toAbsolutePath().normalize()).invariantSeparatorsPathString

    private data class MarkdownSection(
        val found: Boolean,
        val headingLine: Int?,
        val mentions: List<QualityModuleMention>,
        val links: List<MarkdownLink> = emptyList(),
    )

    private data class QualityModuleMention(val path: String, val line: Int?)

    private data class MarkdownLink(val destination: String, val line: Int?)
}
