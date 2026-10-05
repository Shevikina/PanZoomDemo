package com.shevikina.panzoomdemo.core.di

import com.shevikina.panzoomdemo.data.tiling.OpenTiledImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.CalculateViewportUseCase
import com.shevikina.panzoomdemo.domain.usecase.ClampPanOffsetUseCase
import com.shevikina.panzoomdemo.domain.usecase.DebounceLodUseCase
import com.shevikina.panzoomdemo.domain.usecase.DecodeFullImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.GetVisibleTilesUseCase
import com.shevikina.panzoomdemo.domain.usecase.ICalculateViewportUseCase
import com.shevikina.panzoomdemo.domain.usecase.IClampPanOffsetUseCase
import com.shevikina.panzoomdemo.domain.usecase.IDebounceLodUseCase
import com.shevikina.panzoomdemo.domain.usecase.IDecodeFullImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.IGetVisibleTilesUseCase
import com.shevikina.panzoomdemo.domain.usecase.IObserveDeviceMetricsUseCase
import com.shevikina.panzoomdemo.domain.usecase.IOpenTiledImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.IPrepareImageSourceUseCase
import com.shevikina.panzoomdemo.domain.usecase.IRecordJankUseCase
import com.shevikina.panzoomdemo.domain.usecase.IResolveSampleSizeUseCase
import com.shevikina.panzoomdemo.domain.usecase.ObserveDeviceMetricsUseCase
import com.shevikina.panzoomdemo.domain.usecase.PrepareImageSourceUseCase
import com.shevikina.panzoomdemo.domain.usecase.RecordJankUseCase
import com.shevikina.panzoomdemo.domain.usecase.ResolveSampleSizeUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Use case-ы не используют состояния, поэтому указаны без скоупа.
 **/
@Module
@InstallIn(SingletonComponent::class)
internal abstract class UseCaseModule {

    @Binds
    abstract fun bindOpenTiledImageUseCase(impl: OpenTiledImageUseCase): IOpenTiledImageUseCase

    @Binds
    abstract fun bindCalculateViewportUseCase(impl: CalculateViewportUseCase): ICalculateViewportUseCase

    @Binds
    abstract fun bindGetVisibleTilesUseCase(impl: GetVisibleTilesUseCase): IGetVisibleTilesUseCase

    @Binds
    abstract fun bindResolveSampleSizeUseCase(impl: ResolveSampleSizeUseCase): IResolveSampleSizeUseCase

    @Binds
    abstract fun bindDebounceLodUseCase(impl: DebounceLodUseCase): IDebounceLodUseCase

    @Binds
    abstract fun bindClampPanOffsetUseCase(impl: ClampPanOffsetUseCase): IClampPanOffsetUseCase

    @Binds
    abstract fun bindPrepareImageSourceUseCase(impl: PrepareImageSourceUseCase): IPrepareImageSourceUseCase

    @Binds
    abstract fun bindDecodeFullImageUseCase(impl: DecodeFullImageUseCase): IDecodeFullImageUseCase

    @Binds
    abstract fun bindObserveDeviceMetricsUseCase(impl: ObserveDeviceMetricsUseCase): IObserveDeviceMetricsUseCase

    @Binds
    abstract fun bindRecordJankUseCase(impl: RecordJankUseCase): IRecordJankUseCase
}
