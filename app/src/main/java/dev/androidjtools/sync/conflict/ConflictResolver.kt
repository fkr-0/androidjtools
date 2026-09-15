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

/**
 * v1 deliberately fails closed for automatic field-wise merging.
 *
 * The accepted protocol can label a conflict `safe_fieldwise`, but its conflict receipt/schema does
 * not carry a bounded list of fields that the server/operation contract declares independently
 * merge-safe. The decision explicitly permits automatic merging only when that authoritative
 * operation evidence exists. A caller-provided allowlist is not authority, so v1 cannot legally
 * manufacture one. Until a versioned contract adds such evidence, every conflict remains an
 * explicit user-resolution case even when its server merge class is SAFE_FIELDWISE.
 */
object ConflictResolver {
    fun resolve(conflict: ConflictCase): ConflictResolution = conflict.requiresUser()

    private fun ConflictCase.requiresUser() = ConflictResolution.RequiresUser(
        code = code,
        mergeClass = mergeClass,
        localChanges = localChanges,
        remoteChanges = remoteChanges,
        baseRevision = baseRevision,
        authoritativeRevision = authoritativeRevision,
    )
}
