package com.shevikina.panzoomdemo.domain.model

/**
 * Тайл, декодированный с конкретным уровнем детализации.
 * [sampleSize] нужен, чтобы отличать тайлы разных LOD в двухслойном кеше.
 */
data class DecodedTile(
    val rect: TileRect,
    val sampleSize: Int,
    val image: DecodedImage,
)
