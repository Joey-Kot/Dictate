package com.joeykot.dictate.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredApiKeySelectionTest {
    @Test
    fun primaryValueTakesPrecedence() {
        assertEquals(
            StoredApiKeySelection.Primary("new"),
            selectStoredApiKey(
                primaryEncoded = "new",
                migrationComplete = true,
                legacyEncoded = "old",
            ),
        )
    }

    @Test
    fun migrationMarkerPreventsClearedKeyFromReviving() {
        assertEquals(
            StoredApiKeySelection.Empty,
            selectStoredApiKey(
                primaryEncoded = null,
                migrationComplete = true,
                legacyEncoded = "old",
            ),
        )
    }

    @Test
    fun legacyValueRemainsReadableBeforeMigration() {
        assertEquals(
            StoredApiKeySelection.Legacy("old"),
            selectStoredApiKey(
                primaryEncoded = null,
                migrationComplete = false,
                legacyEncoded = "old",
            ),
        )
    }
}
