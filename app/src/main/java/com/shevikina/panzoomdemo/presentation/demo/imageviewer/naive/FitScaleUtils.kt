package com.shevikina.panzoomdemo.presentation.demo.imageviewer.naive

/**
 * Считает множитель, вписывающий картинку в экран целиком.
 *
 * Отрисовка и кламп берут одно и то же число.
 * Если они разойдутся, то картинку можно будет утащить за край экрана.
 */
internal fun fitScale(
    viewportW: Float,
    viewportH: Float,
    imageW: Int,
    imageH: Int,
): Float = minOf(viewportW / imageW, viewportH / imageH)
