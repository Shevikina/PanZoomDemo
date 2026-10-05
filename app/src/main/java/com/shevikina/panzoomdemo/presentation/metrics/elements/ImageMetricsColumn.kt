package com.shevikina.panzoomdemo.presentation.metrics.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import com.shevikina.panzoomdemo.presentation.metrics.TileCountsState
import com.shevikina.panzoomdemo.presentation.metrics.color.METRIC_LABEL_COLOR
import com.shevikina.panzoomdemo.presentation.metrics.color.tilesColor
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendLabel
import com.shevikina.panzoomdemo.presentation.metrics.utils.appendMetric

/**
 * Столбец метрик с данными отображаемого изображения.
 *
 * Размер файла красится нейтрально, потому что это факт, а не статус.
 *
 * Счётчик тайлов стоит здесь же, потому что говорит про то же изображение. Рисовать его
 * во вьюере значило бы связывать две ветки дерева подгонкой отступов.
 */
@Composable
internal fun ImageMetricsColumn(
    imageSize: String,
    tileCounts: TileCountsState,
    showTiles: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(METRICS_ITEM_SPACING),
    ) {
        MetricsOverlayItem(
            text = "Full Image: $imageSize",
            textColor = METRIC_LABEL_COLOR,
        )
        if (showTiles) {
            val held = tileCounts.held
            val needed = tileCounts.needed
            MetricsOverlayItem(
                text = buildAnnotatedString {
                    appendLabel("tiles: ")
                    appendMetric("$held/$needed", tilesColor(held, needed))
                },
            )
        }
    }
}
