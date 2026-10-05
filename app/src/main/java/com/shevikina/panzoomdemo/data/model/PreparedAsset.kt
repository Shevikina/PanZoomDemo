package com.shevikina.panzoomdemo.data.model

import java.io.File

/**
 * Ассет, готовый к региональному декодированию.
 *
 * Размер возвращается вместе с файлом, потому что подготовка уже прочитала его при сверке
 * копии, и второй поход в `AssetManager` ради того же числа не нужен.
 */
internal data class PreparedAsset(
    val file: File,
    val sizeBytes: Long,
)
