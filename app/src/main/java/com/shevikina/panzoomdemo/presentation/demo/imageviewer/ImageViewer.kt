package com.shevikina.panzoomdemo.presentation.demo.imageviewer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shevikina.panzoomdemo.domain.model.ImageLoadError
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive.NaiveImageViewer
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.TiledImageViewer
import com.shevikina.panzoomdemo.presentation.demo.model.ViewerUi

/**
 * Единая точка входа вьюеров.
 *
 * Показывает вьюер того режима, который пришёл в [viewer], и отдаёт ему параметры этого режима.
 * Готовность источника проверена раньше, в маппере, поэтому здесь рисовать уже есть что.
 */
@Composable
fun ImageViewer(
    viewer: ViewerUi,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onError: (ImageLoadError) -> Unit = {},
    onTilesChanged: (held: Int, needed: Int) -> Unit = { _, _ -> },
) {
    when (viewer) {
        is ViewerUi.Naive -> NaiveImageViewer(
            asset = viewer.asset,
            modifier = modifier,
            onReady = onReady,
            onError = onError,
        )

        is ViewerUi.Tiled -> TiledImageViewer(
            source = viewer.source,
            checkedCulling = viewer.culling,
            checkedLod = viewer.lod,
            useGraphicsLayer = viewer.useGraphicsLayer,
            modifier = modifier,
            onReady = onReady,
            onError = onError,
            onTilesChanged = onTilesChanged,
        )
    }
}
