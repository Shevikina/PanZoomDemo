package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.shevikina.panzoomdemo.core.log.DemoLog

/**
 * Пишет в лог номер рекомпозиции вызывающего композабла.
 *
 * `SideEffect` срабатывает после успешной рекомпозиции и сам её не вызывает, поэтому счётчик
 * ничего не искажает. В тайловом режиме строки должны появляться редко, и это их главный смысл.
 *
 * @param tag имя композабла в строке лога.
 * @param details хвост строки со счётчиками, которые стоит видеть рядом с номером.
 */
@Composable
internal fun RecomposeLogEffect(
    tag: String,
    details: () -> String,
) {
    val recomposeCount = remember { intArrayOf(0) }
    SideEffect {
        recomposeCount[0]++
        DemoLog.hot { "recompose[$tag] #${recomposeCount[0]} | ${details()}" }
    }
}
