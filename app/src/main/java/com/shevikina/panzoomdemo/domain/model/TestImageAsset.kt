package com.shevikina.panzoomdemo.domain.model

/**
 * Перечисление ассетов с тестовыми изображениями разных размеров
 */
enum class TestImageAsset(
    val fileName: String,
) {
    SMALL("test_image_small.jpg"),
    MEDIUM("test_image_medium.jpg"),
    LARGE("test_image_large.jpg"),
}
