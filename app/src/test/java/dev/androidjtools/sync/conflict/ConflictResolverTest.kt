package dev.androidjtools.sync.conflict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {
    @Test
    fun `authoritative safe fieldwise disjoint edits auto merge including deletion`() {
        val conflict = ConflictCase(
            mutationId = "mutation-100",
            mergeClass = ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("rating" to "5", "comments" to null),
            remoteChanges = mapOf("genre" to "garage"),
            mergeSafeFields = setOf("rating", "comments", "genre"),
            code = "independent_fields",
            baseRevision = "rev:base",
            authoritativeRevision = "rev:remote",
        )

        val resolution = ConflictResolver.resolve(conflict)

        assertEquals(
            ConflictResolution.AutoMerged(
                mapOf("genre" to "garage", "rating" to "5", "comments" to null),
            ),
            resolution,
        )
    }

    @Test
    fun `same field never auto merges`() {
        val conflict = ConflictCase(
            "mutation-101",
            ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("rating" to "5"),
            remoteChanges = mapOf("rating" to "3"),
            mergeSafeFields = setOf("rating"),
            code = "same_field",
        )
        assertRequiresUser(conflict, ConflictResolver.resolve(conflict))
    }

    @Test
    fun `safe label without authoritative fields and undeclared fields fail closed`() {
        val noAuthority = ConflictCase(
            "mutation-102a",
            ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("title" to "local"),
            remoteChanges = mapOf("rating" to "3"),
            code = "missing_merge_authority",
        )
        assertRequiresUser(noAuthority, ConflictResolver.resolve(noAuthority))

        val undeclared = ConflictCase(
            "mutation-102b",
            ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("title" to "local"),
            remoteChanges = mapOf("rating" to "3"),
            mergeSafeFields = setOf("rating"),
            code = "field_not_declared_safe_by_protocol",
        )
        assertRequiresUser(undeclared, ConflictResolver.resolve(undeclared))
    }

    @Test
    fun `ordered collection range overlap delete edit identity and schema conflicts require user`() {
        val classes = listOf(
            ConflictMergeClass.ORDERED_COLLECTION,
            ConflictMergeClass.EDITOR_AGGREGATE,
            ConflictMergeClass.DELETE_VS_EDIT,
            ConflictMergeClass.IDENTITY_CONFLICT,
            ConflictMergeClass.INCOMPATIBLE_SCHEMA,
        )
        classes.forEachIndexed { index, mergeClass ->
            val conflict = ConflictCase(
                mutationId = "mutation-20$index",
                mergeClass = mergeClass,
                localChanges = mapOf("range" to "10-20"),
                remoteChanges = mapOf("range" to "15-25"),
                mergeSafeFields = setOf("range"),
                code = mergeClass.name.lowercase(),
                baseRevision = "rev:base",
                authoritativeRevision = "rev:remote",
            )
            assertRequiresUser(conflict, ConflictResolver.resolve(conflict))
        }
    }

    private fun assertRequiresUser(conflict: ConflictCase, resolution: ConflictResolution) {
        assertTrue(resolution is ConflictResolution.RequiresUser)
        resolution as ConflictResolution.RequiresUser
        assertEquals(conflict.code, resolution.code)
        assertEquals(conflict.mergeClass, resolution.mergeClass)
        assertEquals(conflict.localChanges, resolution.localChanges)
        assertEquals(conflict.remoteChanges, resolution.remoteChanges)
        assertEquals(conflict.baseRevision, resolution.baseRevision)
        assertEquals(conflict.authoritativeRevision, resolution.authoritativeRevision)
    }
}
