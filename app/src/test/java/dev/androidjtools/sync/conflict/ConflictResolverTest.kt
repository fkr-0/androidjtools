package dev.androidjtools.sync.conflict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {
    @Test
    fun `safe fieldwise server label does not invent field authority`() {
        val conflict = ConflictCase(
            mutationId = "mutation-100",
            mergeClass = ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("rating" to "5"),
            remoteChanges = mapOf("comment" to "remote"),
            code = "independent_fields",
            baseRevision = "rev:base",
            authoritativeRevision = "rev:remote",
        )

        val resolution = ConflictResolver.resolve(conflict)

        assertRequiresUser(conflict, resolution)
    }

    @Test
    fun `same field never auto merges`() {
        val conflict = ConflictCase(
            "mutation-101",
            ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("rating" to "5"),
            remoteChanges = mapOf("rating" to "3"),
            code = "same_field",
        )
        assertRequiresUser(conflict, ConflictResolver.resolve(conflict))
    }

    @Test
    fun `caller cannot supply a manufactured safe field allowlist`() {
        val parameterNames = ConflictCase::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("approvedIndependentFields" !in parameterNames)

        val conflict = ConflictCase(
            "mutation-102",
            ConflictMergeClass.SAFE_FIELDWISE,
            localChanges = mapOf("title" to "local"),
            remoteChanges = mapOf("rating" to "3"),
            code = "field_not_declared_safe_by_protocol",
        )
        assertRequiresUser(conflict, ConflictResolver.resolve(conflict))
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
