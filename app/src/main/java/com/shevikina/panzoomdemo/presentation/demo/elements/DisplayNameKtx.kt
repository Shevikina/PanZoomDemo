package com.shevikina.panzoomdemo.presentation.demo.elements

import androidx.annotation.StringRes
import com.shevikina.panzoomdemo.R
import com.shevikina.panzoomdemo.domain.model.TestImageAsset
import com.shevikina.panzoomdemo.domain.model.ViewerMode

/**
 * Подписи кнопок выбора текущего ассета
 */
@get:StringRes
internal val TestImageAsset.displayNameRes: Int
    get() = when (this) {
        TestImageAsset.SMALL -> R.string.demo_asset_small
        TestImageAsset.MEDIUM -> R.string.demo_asset_medium
        TestImageAsset.LARGE -> R.string.demo_asset_large
    }

/**
 * Подписи кнопок выбора текущего вьювера
 */
@get:StringRes
internal val ViewerMode.displayNameRes: Int
    get() = when (this) {
        ViewerMode.NAIVE -> R.string.demo_mode_naive
        ViewerMode.TILED -> R.string.demo_mode_tiled
    }
