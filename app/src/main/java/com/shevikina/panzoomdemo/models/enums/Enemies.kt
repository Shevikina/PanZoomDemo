package com.shevikina.panzoomdemo.models.enums

/**
 * Перечисление "врагов" стабильного pan/zoom
 */
enum class Enemies(val enemyName: String) {
    START_STATE("Стартовое состояние"),
    MEMORY("Память"),
    COST_PER_FRAME("Стоимость кадра")
}