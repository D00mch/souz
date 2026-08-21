package ru.souz.build.quality

internal object ModuleBoundaries {
    private val allowedDependencies = mapOf(
        ModuleScope(":graph-engine", "main") to emptySet(),
        ModuleScope(":llms", "main") to emptySet(),
        ModuleScope(":agent", "main") to setOf(":graph-engine", ":llms"),
        ModuleScope(":native", "main") to setOf(":llms"),
        ModuleScope(":skill-oauth-api", "main") to emptySet(),
        ModuleScope(":skill-oauth-impl", "main") to setOf(":skill-oauth-api"),
        ModuleScope(":sharedLogic", "commonJvmMain") to setOf(":agent", ":llms", ":skill-oauth-api"),
        ModuleScope(":sharedLogic", "jvmMain") to setOf(":native"),
        ModuleScope(":ambientAgent", "jvmMain") to setOf(":sharedLogic"),
        ModuleScope(":sharedUI", "commonJvmMain") to setOf(":ambientAgent", ":sharedLogic", ":agent", ":llms"),
        ModuleScope(":sharedUI", "jvmMain") to setOf(":native"),
        ModuleScope(":backend", "main") to setOf(
            ":agent",
            ":llms",
            ":native",
            ":sharedLogic",
            ":skill-oauth-api",
            ":skill-oauth-impl",
        ),
        ModuleScope(":desktopApp", "main") to setOf(
            ":ambientAgent",
            ":sharedLogic",
            ":sharedUI",
            ":agent",
            ":llms",
            ":native",
        ),
    )

    private val registeredModules = allowedDependencies.keys.mapTo(sortedSetOf(), ModuleScope::module)

    fun check(
        projects: List<ProjectDescriptor>,
        edges: List<DependencyEdge>,
    ): List<QualityDiagnostic> {
        val diagnostics = mutableListOf<QualityDiagnostic>()
        val descriptorsByPath = projects.associateBy(ProjectDescriptor::path)

        projects
            .filterNot { it.path in registeredModules }
            .forEach { project ->
                diagnostics += QualityDiagnostic(
                    path = project.buildFile,
                    line = null,
                    message = "${project.path} has no registered production dependency policy.",
                )
            }

        edges.sortedWith(compareBy(DependencyEdge::source, DependencyEdge::sourceSet, DependencyEdge::target))
            .forEach { edge ->
                val policy = allowedDependencies[ModuleScope(edge.source, edge.sourceSet)]
                when {
                    edge.source !in descriptorsByPath -> diagnostics += QualityDiagnostic(
                        path = edge.buildFile,
                        line = null,
                        message = "Dependency edge has unknown source project ${edge.source}.",
                    )

                    edge.target !in descriptorsByPath -> diagnostics += QualityDiagnostic(
                        path = edge.buildFile,
                        line = null,
                        message = "${edge.source} declares an edge to unknown product project ${edge.target}.",
                    )

                    edge.sourceSet == UNCLASSIFIED_SOURCE_SET -> diagnostics += QualityDiagnostic(
                        path = edge.buildFile,
                        line = null,
                        message = "${edge.source} declares a project dependency on ${edge.target} from " +
                            "unclassified configuration ${edge.configuration}.",
                    )

                    policy == null -> diagnostics += QualityDiagnostic(
                        path = edge.buildFile,
                        line = null,
                        message = "${edge.source} has no dependency policy for ${edge.sourceSet} " +
                            "(${edge.configuration}) before declaring ${edge.target}.",
                    )

                    edge.target !in policy -> diagnostics += QualityDiagnostic(
                        path = edge.buildFile,
                        line = null,
                        message = "${edge.source} ${edge.sourceSet} must not depend on ${edge.target} " +
                            "(${edge.configuration}).",
                    )
                }
            }

        return diagnostics
    }

    private data class ModuleScope(val module: String, val sourceSet: String)
}
