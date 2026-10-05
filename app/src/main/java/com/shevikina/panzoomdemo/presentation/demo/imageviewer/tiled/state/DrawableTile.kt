package com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state

import androidx.compose.ui.graphics.ImageBitmap
import com.shevikina.panzoomdemo.domain.model.DecodedImage
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.toImageBitmap

/**
 * Тайл, готовый к отрисовке.
 *
 * Обёртка [ImageBitmap] создаётся один раз на тайл, а не на каждый вызов `drawImage`.
 */
class DrawableTile internal constructor(
    private val source: DecodedImage,
) {

    /**
     * Обёртка над буфером для отрисовки.
     */
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.NONE) { source.toImageBitmap() }

    /** Возвращает буфер владельцу; после этого больше нельзя будет отрисовывать текущий тайл. */
    fun release() = source.release()
}
