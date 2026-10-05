package com.shevikina.panzoomdemo.presentation.demo.model

import com.shevikina.panzoomdemo.domain.model.ImageSource
import com.shevikina.panzoomdemo.domain.model.TestImageAsset

/**
 * Вьюер, который показывает экран, вместе с параметрами своего режима.
 *
 * Каждый режим несёт только то, чем пользуется.
 */
sealed interface ViewerUi {

    /** Картинка декодируется в память целиком, поэтому кроме ассета ничего не нужно. */
    data class Naive(
        val asset: TestImageAsset,
    ) : ViewerUi

    /** Картинка читается тайлами, а тумблеры включают оптимизации по одной. */
    data class Tiled(
        val source: ImageSource,
        val culling: Boolean,
        val lod: Boolean,
        val useGraphicsLayer: Boolean,
    ) : ViewerUi
}
