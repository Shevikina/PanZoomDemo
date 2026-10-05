package com.shevikina.panzoomdemo.test.stub

import com.shevikina.panzoomdemo.domain.model.DecodedImage

/** Тайл без платформенного битмапа: считает вызовы [release]. */
class FakeDecodedImage(
    override val width: Int = 512,
    override val height: Int = 512,
) : DecodedImage {

    var releaseCount = 0
        private set

    override fun release() {
        releaseCount++
    }
}
