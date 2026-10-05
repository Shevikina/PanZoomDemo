package com.shevikina.panzoomdemo.data.mapper

import android.graphics.Rect
import com.shevikina.panzoomdemo.domain.model.TileRect

/**
 * Преобразования между доменным [TileRect] и платформенным [Rect].
 *
 * Вызывается только на границе слоя data, перед декодом, но не в цикле отрисовки.
 * При сильном отдалении набор тайлов доходит до тысяч, и маппинг каждый кадр создавал бы
 * лишнее давление на сборщик мусора.
 */
internal fun TileRect.toAndroid(): Rect = Rect(left, top, right, bottom)
