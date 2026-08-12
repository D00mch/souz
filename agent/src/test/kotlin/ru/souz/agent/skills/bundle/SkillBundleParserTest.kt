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
}
