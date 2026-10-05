package com.shevikina.panzoomdemo.presentation.metrics.utils

internal const val BYTES_PER_MB = 1_048_576L
private const val BYTES_PER_KB = 1_024L

/**
 * Переводит размер файла в строку с самой крупной подходящей единицей.
 *
 * Мегабайты для тестовых картинок, килобайты и байты остаются для мелких файлов,
 * чтобы на экране не было нулей вместо размера.
 */
internal fun formatImageSize(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    val kb = bytes / BYTES_PER_KB
    return when {
        mb > 0 -> "${mb}Mb"
        kb > 0 -> "${kb}Kb"
        else -> "${bytes}bytes"
    }
}
