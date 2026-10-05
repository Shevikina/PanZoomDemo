package com.shevikina.panzoomdemo.domain.model

/**
 * Подготовленный источник изображения: seekable-файл, пригодный для регионального декодирования.
 *
 * @param path абсолютный путь к файлу.
 * @param sizeBytes размер исходного ассета, для оверлея метрик.
 */
data class ImageSource(
    val asset: TestImageAsset,
    val path: String,
    val sizeBytes: Long,
)
