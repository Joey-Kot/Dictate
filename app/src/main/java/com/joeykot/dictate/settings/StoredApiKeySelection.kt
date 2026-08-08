package com.joeykot.dictate.settings

internal sealed interface StoredApiKeySelection {
    data class Primary(val encoded: String) : StoredApiKeySelection

    data class Legacy(val encoded: String) : StoredApiKeySelection

    data object Empty : StoredApiKeySelection
}

internal fun selectStoredApiKey(
    primaryEncoded: String?,
    migrationComplete: Boolean,
    legacyEncoded: String?,
): StoredApiKeySelection = when {
    primaryEncoded != null -> StoredApiKeySelection.Primary(primaryEncoded)
    migrationComplete -> StoredApiKeySelection.Empty
    legacyEncoded != null -> StoredApiKeySelection.Legacy(legacyEncoded)
    else -> StoredApiKeySelection.Empty
}
