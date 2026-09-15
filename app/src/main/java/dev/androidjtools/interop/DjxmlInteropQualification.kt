package dev.androidjtools.interop

enum class DjxmlSemantic {
    STABLE_ASSET_IDENTITY,
    CUE,
    LOOP,
    REGION,
    NAMESPACED_TAG,
    CRATE_MEMBERSHIP,
    PLAYLIST_IDENTITY,
    PLAYLIST_ORDER,
}

enum class DjxmlQualificationState {
    QUALIFIED,
    ADAPTER_SUPPORTED_BUT_REAL_SYNC_BLOCKED,
    BLOCKED_NO_CANONICAL_PLAYLIST_AUTHORITY,
}

data class DjxmlInteropEnvironment(
    val djxmlMajorVersion: Int,
    val realSyncFacadeAvailable: Boolean,
    val canonicalPlaylistAuthorityAvailable: Boolean,
)

data class DjxmlSemanticQualification(
    val semantic: DjxmlSemantic,
    val state: DjxmlQualificationState,
    val reason: String,
)

data class DjxmlInteropQualification(
    val semantics: List<DjxmlSemanticQualification>,
) {
    fun stateOf(semantic: DjxmlSemantic): DjxmlQualificationState =
        semantics.single { it.semantic == semantic }.state

    val fullyQualified: Boolean
        get() = semantics.all { it.state == DjxmlQualificationState.QUALIFIED }
}

/**
 * Fail-closed EPIC-13 qualification policy.
 *
 * DJXML is an exchange projection owned by Sample Lib. Android never promotes XML, a file path,
 * DJXML track number, filename, or list position into canonical identity. Stable identity is the
 * Sample Lib asset id carried by the adapter as `Grouping=sample-lib:<asset-id>`.
 *
 * The current Sample Lib adapter supports preparation semantics, but real Android round-trip claims
 * additionally require the production Sample Lib v1 sync facade accepted by EPIC-12. Crate tags
 * are useful interoperability metadata but are not canonical ordered playlist identity.
 */
object DjxmlInteropPolicy {
    private val adapterSemantics = setOf(
        DjxmlSemantic.STABLE_ASSET_IDENTITY,
        DjxmlSemantic.CUE,
        DjxmlSemantic.LOOP,
        DjxmlSemantic.REGION,
        DjxmlSemantic.NAMESPACED_TAG,
        DjxmlSemantic.CRATE_MEMBERSHIP,
    )

    fun evaluate(environment: DjxmlInteropEnvironment): DjxmlInteropQualification {
        require(environment.djxmlMajorVersion == 2) {
            "EPIC-13 only qualifies the Sample Lib DJXML v2 adapter"
        }
        return DjxmlInteropQualification(
            DjxmlSemantic.entries.map { semantic ->
                when {
                    semantic in adapterSemantics && !environment.realSyncFacadeAvailable ->
                        DjxmlSemanticQualification(
                            semantic,
                            DjxmlQualificationState.ADAPTER_SUPPORTED_BUT_REAL_SYNC_BLOCKED,
                            "Sample Lib DJXML v2 supports this semantic, but production /v1/sync is unavailable, so Android real-system round-trip is unverified.",
                        )
                    semantic in adapterSemantics ->
                        DjxmlSemanticQualification(
                            semantic,
                            DjxmlQualificationState.QUALIFIED,
                            "Sample Lib DJXML v2 plus the real sync facade can preserve this canonical semantic projection.",
                        )
                    !environment.canonicalPlaylistAuthorityAvailable ->
                        DjxmlSemanticQualification(
                            semantic,
                            DjxmlQualificationState.BLOCKED_NO_CANONICAL_PLAYLIST_AUTHORITY,
                            "Sample Lib currently projects collections as crate tags; canonical playlist/item identity and ordering are not available.",
                        )
                    !environment.realSyncFacadeAvailable ->
                        DjxmlSemanticQualification(
                            semantic,
                            DjxmlQualificationState.ADAPTER_SUPPORTED_BUT_REAL_SYNC_BLOCKED,
                            "Canonical playlist authority exists, but Android real-system round-trip still requires production /v1/sync.",
                        )
                    else ->
                        DjxmlSemanticQualification(
                            semantic,
                            DjxmlQualificationState.QUALIFIED,
                            "Canonical playlist identity/order and the real sync facade are both available.",
                        )
                }
            },
        )
    }

    fun assetGrouping(assetId: String): String {
        require(assetId.isNotBlank()) { "assetId must be a stable non-blank Sample Lib identity" }
        return "sample-lib:$assetId"
    }

    fun assetIdFromGrouping(grouping: String): String? =
        grouping.removePrefix("sample-lib:")
            .takeIf { grouping.startsWith("sample-lib:") && it.isNotBlank() }
}
