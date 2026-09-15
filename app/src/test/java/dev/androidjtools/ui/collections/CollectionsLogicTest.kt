package dev.androidjtools.ui.collections

import dev.androidjtools.core.provider.ProviderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionsLogicTest {
    @Test
    fun `provider states map to explicit collection states`() {
        assertEquals(CollectionsSurfaceState.Ready, ProviderUiState.Ready.toCollectionsSurfaceState())
        assertEquals(CollectionsSurfaceState.Offline, ProviderUiState.Offline.toCollectionsSurfaceState())
        assertEquals(CollectionsSurfaceState.Conflict("c"), ProviderUiState.Conflict("c").toCollectionsSurfaceState())
        assertEquals(CollectionsSurfaceState.Error("e"), ProviderUiState.Error("e").toCollectionsSurfaceState())
    }

    @Test
    fun `ready has no banner while offline and conflict explain local edit semantics`() {
        assertNull(CollectionsSurfaceState.Ready.statusCopy())
        assertNotNull(CollectionsSurfaceState.Offline.statusCopy())
        assertEquals("Conflict requires review", CollectionsSurfaceState.Conflict("revision mismatch").statusCopy()?.first)
    }
}
