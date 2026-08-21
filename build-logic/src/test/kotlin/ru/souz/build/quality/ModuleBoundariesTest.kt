package ru.souz.build.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModuleBoundariesTest {
    @Test
    fun `allows reviewed edges and rejects a new production edge`() {
        val projects = listOf(
            ProjectDescriptor(":graph-engine", "graph-engine", "graph-engine/build.gradle.kts"),
            ProjectDescriptor(":llms", "llms", "llms/build.gradle.kts"),
            ProjectDescriptor(":agent", "agent", "agent/build.gradle.kts"),
        )
        val allowed = DependencyEdge(
            source = ":agent",
            configuration = "implementation",
            sourceSet = "main",
            target = ":llms",
            buildFile = "agent/build.gradle.kts",
        )
        val forbidden = DependencyEdge(
            source = ":graph-engine",
            configuration = "implementation",
            sourceSet = "main",
            target = ":llms",
            buildFile = "graph-engine/build.gradle.kts",
        )

        assertEquals(emptyList<QualityDiagnostic>(), ModuleBoundaries.check(projects, listOf(allowed)))
        assertEquals(1, ModuleBoundaries.check(projects, listOf(forbidden)).size)
    }

    @Test
    fun `recognizes production source sets without treating tests as production`() {
        assertEquals("main", dependencySourceSet("implementation"))
        assertEquals("main", dependencySourceSet("compileOnlyApi"))
        assertEquals("main", dependencySourceSet("annotationProcessor"))
        assertEquals("commonJvmMain", dependencySourceSet("commonJvmMainImplementation"))
        assertEquals("jvmMain", dependencySourceSet("jvmMainCompileOnlyApi"))
        assertNull(dependencySourceSet("testImplementation"))
        assertNull(dependencySourceSet("jvmTestImplementation"))
        assertNull(dependencySourceSet("testFixturesImplementation"))
        assertEquals(UNCLASSIFIED_SOURCE_SET, dependencySourceSet("internal"))
    }
}
