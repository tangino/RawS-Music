package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Owns artwork source precedence independently from bitmap tier/cache policy.
 *
 * An audio file's embedded picture is authoritative for that file. Folder images are reusable
 * fallbacks, but they must never bypass a still-unchecked embedded source simply because a sibling
 * request indexed folder.jpg first. Keeping this rule here prevents the player, full-screen viewer,
 * and list/indexer lanes from silently inventing different source orders.
 */
internal object ArtworkSourceSelectionPolicy {
    enum class IndexedSourceKind {
        Embedded,
        FolderCover,
        DirectImage
    }

    val embeddedDecodeOrder: List<RawArtworkPolicy.DecodeStage> = listOf(
        RawArtworkPolicy.DecodeStage.RegionHandle,
        RawArtworkPolicy.DecodeStage.NativeSource,
        RawArtworkPolicy.DecodeStage.Ffmpeg,
        RawArtworkPolicy.DecodeStage.MediaMetadataRetriever
    )

    val audioDecodeOrder: List<RawArtworkPolicy.DecodeStage> =
        embeddedDecodeOrder + RawArtworkPolicy.DecodeStage.FolderCover

    val indexedLookupOrder: List<IndexedSourceKind> = listOf(
        IndexedSourceKind.Embedded,
        IndexedSourceKind.DirectImage,
        IndexedSourceKind.FolderCover
    )

    val embeddedIndexedKinds: Set<IndexedSourceKind> = setOf(IndexedSourceKind.Embedded)
    val folderIndexedKinds: Set<IndexedSourceKind> = setOf(IndexedSourceKind.FolderCover)
    val allIndexedKinds: Set<IndexedSourceKind> = IndexedSourceKind.values().toSet()

    fun isAudioArtworkKey(key: String): Boolean {
        if (key.startsWith("audio://")) return true
        val lower = key.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") ||
            lower.contains(".jpg?") || lower.contains(".jpeg?") || lower.contains(".png?") ||
            lower.contains(".webp?") || lower.contains("photo_new") || lower.contains("cover")
        ) {
            return false
        }
        val raw = when {
            key.startsWith("file://") -> key.removePrefix("file://")
            else -> key
        }.substringBefore('|')
            .substringBefore('?')
            .substringBefore('#')
        val extension = raw.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wma", "wav",
            "aiff", "aif", "ape", "wv", "tta", "dsf", "dff", "mka", "amr", "iso"
        )
    }
}
