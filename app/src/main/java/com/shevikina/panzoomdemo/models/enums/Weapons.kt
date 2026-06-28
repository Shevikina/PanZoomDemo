package com.shevikina.panzoomdemo.models.enums

/**
 * Перечисление "оружия"/инструментов для стабильного pan/zoom
 */
enum class Weapons(val weaponName: String) {
    START_STATE("Стартовое состояние"),
    REGION_DECODE("Региональное декодипрование"),
    SUBSAMPLING("Субсемплинг"),
    REUSE_BITMAP("Переиспользование битмапов"),
    LATE_TRANSFORMATION("Поздняя трансформация")
}