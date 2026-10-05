package com.shevikina.panzoomdemo.domain.usecase

import com.shevikina.panzoomdemo.domain.model.PanBounds
import com.shevikina.panzoomdemo.domain.model.PanOffset

/**
 * Ограничивает pan-офсет так, чтобы изображение не ушло полностью за экран.
 * Пока границы не измерены (`PanBounds.UNSPECIFIED`), офсет возвращается без изменений.
 */
interface IClampPanOffsetUseCase {

    operator fun invoke(
        offset: PanOffset,
        scale: Float,
        bounds: PanBounds,
    ): PanOffset
}
