package ru.souz.agent.skills.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillBundleParserTest {

    @Test
    fun `oauthProvider and oauthScopes default to absent when not declared`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: paper
            description: summarizes papers
            ---
            body
            """.trimIndent()
        )

        assertEquals(null, manifest.oauthProvider)
        assertEquals(emptyList(), manifest.oauthScopes)
    }

    @Test
    fun `oauthProvider and oauthScopes parse from frontmatter`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
              - cloud_api:disk.read
              - login:info
            ---
            body
            """.trimIndent()
        )

        assertEquals("yandex", manifest.oauthProvider)
        assertEquals(listOf("cloud_api:disk.read", "login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes parses an inline YAML list`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: [cloud_api:disk.read, login:info]
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("cloud_api:disk.read", "login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes inline list preserves a comma inside a quoted scope`() {
        // Regression test: a plain split(",") turned this single scope into two
        // ("resource" and "read") instead of preserving the comma as part of the token.
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: ["resource,read", login:info]
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("resource,read", "login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes inline empty list parses to no scopes`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: []
            ---
            body
            """.trimIndent()
        )

        assertEquals(emptyList(), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes block list tolerates blank lines and comments before the first item`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:

              # narrow read-only scope first
              - cloud_api:disk.read
              - login:info
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("cloud_api:disk.read", "login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes block list parses with a single-space indent`() {
        // Regression test: YAML doesn't mandate a specific indent width, but the parser used to
        // hardcode a two-space minimum, so a validly-indented single-space list silently parsed as
        // zero scopes instead of either accepting or rejecting it.
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
             - cloud_api:disk.read
             - login:info
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("cloud_api:disk.read", "login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes strips a trailing comment from a block list item`() {
        // Regression test: without stripping, the stored scope became the literal string
        // "login:info # basic profile access", which buildAuthorizeUrl then joins into the
        // provider's scope parameter as extra bogus tokens (#, basic, profile, access).
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
              - login:info # basic profile access
              - cloud_api:disk.read
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("login:info", "cloud_api:disk.read"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes strips a trailing comment from an inline list`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: [login:info] # basic profile access
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("login:info"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes does not treat a quoted hash as a comment`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
              - "weird#scope"
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("weird#scope"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes does not treat a hash glued to a token as a comment`() {
        // Per YAML, '#' only starts a comment when preceded by whitespace (or at line start) —
        // not mid-token.
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
              - login:info#not-a-comment
            ---
            body
            """.trimIndent()
        )

        assertEquals(listOf("login:info#not-a-comment"), manifest.oauthScopes)
    }

    @Test
    fun `oauthScopes treats an explicit null or bare value as no scopes`() {
        val explicitNull = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: null
            ---
            body
            """.trimIndent()
        )
        val tildeNull = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes: ~
            ---
            body
            """.trimIndent()
        )

        assertEquals(emptyList(), explicitNull.oauthScopes)
        assertEquals(emptyList(), tildeNull.oauthScopes)
    }

    @Test
    fun `oauthScopes rejects a scalar value instead of a list`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: yandex-disk
                description: reads files from Yandex Disk
                oauthProvider: yandex
                oauthScopes: login:info
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `oauthProvider strips a trailing comment`() {
        // Regression test: without stripping, "oauthProvider: yandex # production" stored the
        // literal value "yandex # production", so the provider was never found by lookup.
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex # production
            ---
            body
            """.trimIndent()
        )

        assertEquals("yandex", manifest.oauthProvider)
    }

    @Test
    fun `metadata values strip a trailing comment`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            metadata:
              tier: premium # billed separately
            ---
            body
            """.trimIndent()
        )

        assertEquals("premium", manifest.metadata["tier"])
    }

    @Test
    fun `oauthScopes rejects a malformed block list item`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: yandex-disk
                description: reads files from Yandex Disk
                oauthProvider: yandex
                oauthScopes:
                  login:info
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `commands default to empty when not declared`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: paper
            description: summarizes papers
            ---
            body
            """.trimIndent()
        )

        assertEquals(emptyMap(), manifest.commands)
    }

    @Test
    fun `a composite command with tool, script, and wait steps parses`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: tv-control
            description: controls a TV
            commands:
              locate:
                inputs: [device, target]
                steps:
                  - id: screenshot
                    tool: device.mcp.call_tool
                    arguments:
                      name: get_screenshot
                      target: "${'$'}{inputs.device}"
                  - id: settle
                    waitMs: 1500
                  - id: located
                    script: scripts/vision_locate.py
                    runtime: PYTHON
                    args: ["${'$'}{screenshot.content}", "${'$'}{inputs.target}"]
                returns: "${'$'}{located}"
            ---
            body
            """.trimIndent()
        )

        val locate = manifest.commands.getValue("locate")
        assertEquals(listOf("device", "target"), locate.inputs)
        assertEquals(3, locate.steps.size)
        assertEquals("device.mcp.call_tool", locate.steps[0].tool)
        assertEquals(1500L, locate.steps[1].waitMs)
        assertEquals("scripts/vision_locate.py", locate.steps[2].script)
        assertEquals("PYTHON", locate.steps[2].runtime)
        assertEquals("\${located}", locate.returns)
    }

    @Test
    fun `a composite step must set exactly one of tool, script, or waitMs`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps:
                      - id: nothing
                    returns: "${'$'}{nothing}"
                ---
                body
                """.trimIndent()
            )
        }
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps:
                      - id: both
                        tool: device.mcp.call_tool
                        waitMs: 500
                    returns: "${'$'}{both}"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a script step without an explicit runtime is rejected`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps:
                      - id: located
                        script: scripts/vision_locate.py
                    returns: "${'$'}{located}"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `duplicate step ids are rejected`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps:
                      - id: dup
                        tool: device.mcp.call_tool
                      - id: dup
                        waitMs: 500
                    returns: "${'$'}{dup}"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `an empty step list is rejected`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps: []
                    returns: "literal"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a reference to an undeclared input is rejected`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    inputs: [target]
                    steps:
                      - id: screenshot
                        tool: device.mcp.call_tool
                        arguments:
                          target: "${'$'}{inputs.device}"
                    returns: "${'$'}{screenshot}"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a reference to a later or unknown step id is rejected`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: tv-control
                description: controls a TV
                commands:
                  locate:
                    steps:
                      - id: screenshot
                        tool: device.mcp.call_tool
                        arguments:
                          previous: "${'$'}{located}"
                      - id: located
                        script: scripts/vision_locate.py
                        runtime: PYTHON
                    returns: "${'$'}{located}"
                ---
                body
                """.trimIndent()
            )
        }
    }

    @Test
    fun `returns may reference any declared step, including one not otherwise referenced`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: tv-control
            description: controls a TV
            commands:
              act_and_observe:
                inputs: [device, action, actionArguments]
                steps:
                  - id: act
                    tool: device.mcp.call_tool
                    arguments:
                      name: "${'$'}{inputs.action}"
                      arguments: "${'$'}{inputs.actionArguments}"
                      target: "${'$'}{inputs.device}"
                  - id: settle
                    waitMs: 1500
                  - id: observe
                    tool: device.mcp.call_tool
                    arguments:
                      name: get_screen
                      target: "${'$'}{inputs.device}"
                returns: "${'$'}{observe}"
            ---
            body
            """.trimIndent()
        )

        assertEquals("\${observe}", manifest.commands.getValue("act_and_observe").returns)
    }
}
