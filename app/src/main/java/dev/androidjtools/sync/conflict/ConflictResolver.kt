package dev.androidjtools.sync.conflict

enum class ConflictMergeClass {
    SAFE_FIELDWISE,
    EDITOR_AGGREGATE,
    ORDERED_COLLECTION,
    DELETE_VS_EDIT,
    IDENTITY_CONFLICT,
    INCOMPATIBLE_SCHEMA,
}

data class ConflictCase(
    val mutationId: String,
    /** Merge class copied from the authoritative server conflict receipt. */
    val mergeClass: ConflictMergeClass,
    val localChanges: Map<String, String?>,
    val remoteChanges: Map<String, String?>,
    /** Fields the authoritative operation contract explicitly permits for independent merging. */
    val mergeSafeFields: Set<String> = emptySet(),
    val code: String,
    val baseRevision: String? = null,
    val authoritativeRevision: String? = null,
)

sealed interface ConflictResolution {
    data class AutoMerged(val values: Map<String, String?>) : ConflictResolution
    data class RequiresUser(
        val code: String,
        val mergeClass: ConflictMergeClass,
        val localChanges: Map<String, String?>,
        val remoteChanges: Map<String, String?>,
        val baseRevision: String?,
        val authoritativeRevision: String?,
    ) : ConflictResolution
}

object ConflictResolver {
    fun resolve(conflict: ConflictCase): ConflictResolution {
        if (conflict.mergeClass != ConflictMergeClass.SAFE_FIELDWISE) return conflict.requiresUser()
        if (conflict.mergeSafeFields.isEmpty()) return conflict.requiresUser()

        val localFields = conflict.localChanges.keys
        val remoteFields = conflict.remoteChanges.keys
        val changedFields = localFields + remoteFields
        val independentlySafe =
            localFields.intersect(remoteFields).isEmpty() &&
                changedFields.isNotEmpty() &&
                changedFields.all(conflict.mergeSafeFields::contains)
        if (!independentlySafe) return conflict.requiresUser()

        // Null is data here: a field deletion must survive the merged projection rather than
        // disappearing through a filterNotNull-style merge.
        return ConflictResolution.AutoMerged(conflict.remoteChanges + conflict.localChanges)
    }

    private fun ConflictCase.requiresUser() = ConflictResolution.RequiresUser(
        code = code,
        mergeClass = mergeClass,
        localChanges = localChanges,
        remoteChanges = remoteChanges,
        baseRevision = baseRevision,
        authoritativeRevision = authoritativeRevision,
    )
}
