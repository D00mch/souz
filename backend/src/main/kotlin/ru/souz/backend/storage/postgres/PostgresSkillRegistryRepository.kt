package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationFinding
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.backend.skills.normalizeSkillBundleHash
import ru.souz.backend.skills.requireSafeSkillId
import ru.souz.backend.skills.requireSkillUserId
import ru.souz.backend.skills.requireSkillValidationPolicyVersion
import ru.souz.backend.skills.validateAndCopySkillBundle

class PostgresSkillRegistryRepository(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> {
        requireSkillUserId(userId)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                select r.user_id, r.skill_id, r.bundle_hash, r.created_at, b.manifest_json
                from user_skill_registrations r
                join skill_bundles b on b.bundle_hash = r.bundle_hash
                where r.user_id = ?
                order by r.skill_id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.toStoredSkill())
                        }
                    }
                }
            }
        }
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> {
        requireSkillUserId(userId)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                select skill_id
                from user_skill_registrations
                where user_id = ?
                order by skill_id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(SkillId(resultSet.getString("skill_id")))
                        }
                    }
                }
            }
        }
    }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill {
        requireSkillUserId(userId)
        val normalizedBundle = validateAndCopySkillBundle(bundle)
        val bundleHash = SkillBundleHasher.hash(normalizedBundle)
        val now = clock.instant()

        return dataSource.write { connection ->
            connection.lockSkillRegistration(userId, normalizedBundle.skillId)
            connection.persistImmutableBundle(normalizedBundle, bundleHash, now)
            connection.upsertSkillRegistration(
                userId = userId,
                skillId = normalizedBundle.skillId,
                bundleHash = bundleHash,
                now = now,
                manifest = normalizedBundle.manifest,
            )
        }
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        requireSkillUserId(userId)
        requireSafeSkillId(skillId)
        return dataSource.read { connection ->
            val metadata = connection.findRegisteredBundle(userId, skillId) ?: return@read null
            val files = connection.loadBundleFiles(metadata.bundleHash)
            val bundle = SkillBundle.fromFiles(skillId, files)
            val actualHash = SkillBundleHasher.hash(bundle)
            if (actualHash != metadata.bundleHash) {
                throw SkillBundleException(
                    "Stored skill bundle hash mismatch for ${skillId.value}: " +
                        "expected ${metadata.bundleHash}, found $actualHash."
                )
            }
            if (bundle.manifest != metadata.manifest) {
                throw SkillBundleException(
                    "Stored skill manifest does not match immutable bundle ${metadata.bundleHash}."
                )
            }
            validateAndCopySkillBundle(bundle)
        }
    }

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? {
        requireSkillUserId(userId)
        requireSafeSkillId(skillId)
        val normalizedHash = normalizeSkillBundleHash(bundleHash)
        requireSkillValidationPolicyVersion(policyVersion)
        return dataSource.read { connection ->
            connection.prepareStatement(
                """
                select user_id, skill_id, bundle_hash, policy_version, approved, findings_json, created_at
                from skill_validations
                where user_id = ? and skill_id = ? and bundle_hash = ? and policy_version = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, skillId.value)
                statement.setString(3, normalizedHash)
                statement.setString(4, policyVersion)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toSkillValidationRecord() else null
                }
            }
        }
    }

    override suspend fun saveValidation(record: SkillValidationRecord) {
        val normalizedRecord = validateRecord(record)
        val now = clock.instant()
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into skill_validations(
                  user_id, skill_id, bundle_hash, policy_version,
                  approved, findings_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, skill_id, bundle_hash, policy_version) do update
                set approved = excluded.approved,
                    findings_json = excluded.findings_json,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, normalizedRecord.userId)
                statement.setString(2, normalizedRecord.skillId.value)
                statement.setString(3, normalizedRecord.bundleHash)
                statement.setString(4, normalizedRecord.policyVersion)
                statement.setBoolean(5, normalizedRecord.approved)
                statement.setJson(6, postgresStorageMapper.writeValueAsString(normalizedRecord.findings))
                statement.setInstant(7, normalizedRecord.createdAt)
                statement.setInstant(8, now)
                statement.executeUpdate()
            }
        }
    }

    private fun validateRecord(record: SkillValidationRecord): SkillValidationRecord {
        requireSkillUserId(record.userId)
        requireSafeSkillId(record.skillId)
        val normalizedHash = normalizeSkillBundleHash(record.bundleHash)
        requireSkillValidationPolicyVersion(record.policyVersion)
        return record.copy(
            bundleHash = normalizedHash,
        )
    }

    private fun Connection.persistImmutableBundle(
        bundle: SkillBundle,
        bundleHash: String,
        createdAt: Instant,
    ) {
        val totalBytes = bundle.files.sumOf { it.content.size }
        val inserted = prepareStatement(
            """
            insert into skill_bundles(
              bundle_hash, manifest_json, file_count, total_bytes, created_at
            )
            values (?, ?, ?, ?, ?)
            on conflict (bundle_hash) do nothing
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, bundleHash)
            statement.setJson(2, postgresStorageMapper.writeValueAsString(bundle.manifest))
            statement.setInt(3, bundle.files.size)
            statement.setInt(4, totalBytes)
            statement.setInstant(5, createdAt)
            statement.executeUpdate()
        }

        if (inserted == 1) {
            prepareStatement(
                """
                insert into skill_bundle_files(bundle_hash, normalized_path, content)
                values (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                bundle.files.forEach { file ->
                    statement.setString(1, bundleHash)
                    statement.setString(2, file.normalizedPath)
                    statement.setBytes(3, file.content)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            return
        }

        val existing = findImmutableBundle(bundleHash)
            ?: throw SkillBundleException("Immutable skill bundle disappeared during insertion: $bundleHash")
        if (existing.manifest != bundle.manifest || existing.files != bundle.files) {
            throw SkillBundleException("Immutable skill bundle hash collision detected: $bundleHash")
        }
    }

    private fun Connection.findImmutableBundle(bundleHash: String): ImmutableBundle? {
        val manifest = prepareStatement(
            "select manifest_json from skill_bundles where bundle_hash = ?"
        ).use { statement ->
            statement.setString(1, bundleHash)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return null
                postgresStorageMapper.readValue<SkillManifest>(resultSet.getString("manifest_json"))
            }
        }
        return ImmutableBundle(manifest, loadBundleFiles(bundleHash))
    }

    private fun Connection.loadBundleFiles(bundleHash: String): List<SkillFile> =
        prepareStatement(
            """
            select normalized_path, content
            from skill_bundle_files
            where bundle_hash = ?
            order by normalized_path
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, bundleHash)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            SkillFile(
                                normalizedPath = resultSet.getString("normalized_path"),
                                content = resultSet.getBytes("content"),
                            )
                        )
                    }
                }
            }
        }

    private fun Connection.upsertSkillRegistration(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        now: Instant,
        manifest: SkillManifest,
    ): StoredSkill = prepareStatement(
        """
        insert into user_skill_registrations(
          user_id, skill_id, bundle_hash, created_at, updated_at
        )
        values (?, ?, ?, ?, ?)
        on conflict (user_id, skill_id) do update
        set bundle_hash = excluded.bundle_hash,
            updated_at = excluded.updated_at
        returning created_at
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, userId)
        statement.setString(2, skillId.value)
        statement.setString(3, bundleHash)
        statement.setInstant(4, now)
        statement.setInstant(5, now)
        statement.executeQuery().use { resultSet ->
            check(resultSet.next()) { "Skill registration upsert returned no row." }
            StoredSkill(
                userId = userId,
                skillId = skillId,
                manifest = manifest,
                bundleHash = bundleHash,
                createdAt = resultSet.instant("created_at"),
            )
        }
    }

    private fun Connection.findRegisteredBundle(
        userId: String,
        skillId: SkillId,
    ): RegisteredBundle? = prepareStatement(
        """
        select r.bundle_hash, b.manifest_json
        from user_skill_registrations r
        join skill_bundles b on b.bundle_hash = r.bundle_hash
        where r.user_id = ? and r.skill_id = ?
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, userId)
        statement.setString(2, skillId.value)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return null
            RegisteredBundle(
                bundleHash = resultSet.getString("bundle_hash"),
                manifest = postgresStorageMapper.readValue(resultSet.getString("manifest_json")),
            )
        }
    }

    private fun Connection.lockSkillRegistration(userId: String, skillId: SkillId) {
        prepareStatement(
            "select pg_advisory_xact_lock(hashtext(?), hashtext(?))"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, skillId.value)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Failed to acquire skill registration lock." }
            }
        }
    }

    private fun ResultSet.toStoredSkill(): StoredSkill = StoredSkill(
        userId = getString("user_id"),
        skillId = SkillId(getString("skill_id")),
        manifest = postgresStorageMapper.readValue(getString("manifest_json")),
        bundleHash = getString("bundle_hash"),
        createdAt = instant("created_at"),
    )

    private fun ResultSet.toSkillValidationRecord(): SkillValidationRecord = SkillValidationRecord(
        userId = getString("user_id"),
        skillId = SkillId(getString("skill_id")),
        bundleHash = getString("bundle_hash"),
        policyVersion = getString("policy_version"),
        approved = getBoolean("approved"),
        findings = postgresStorageMapper.readValue<List<SkillValidationFinding>>(getString("findings_json")),
        createdAt = instant("created_at"),
    )

    private data class RegisteredBundle(
        val bundleHash: String,
        val manifest: SkillManifest,
    )

    private data class ImmutableBundle(
        val manifest: SkillManifest,
        val files: List<SkillFile>,
    )
}
