package com.shevikina.panzoomdemo.models.enums

/**
 * Перечисление ассетов с тестовыми изображениями разных размеров
 */
enum class TestImageAsset(val fileName: String) {
    LARGE("test_image_large.jpg"),
    MEDIUM("test_image_medium.jpg"),
    SMALL("test_image_small.jpg")
}