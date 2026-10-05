package com.shevikina.panzoomdemo.presentation.di

import com.shevikina.panzoomdemo.domain.usecase.IClampPanOffsetUseCase
import com.shevikina.panzoomdemo.domain.usecase.IDecodeFullImageUseCase
import com.shevikina.panzoomdemo.domain.usecase.IObserveDeviceMetricsUseCase
import com.shevikina.panzoomdemo.presentation.demo.imageviewer.tiled.state.TiledImageViewerStateFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Зависимости для composable-функций, у которых нет собственного биндера.
 *
 * Такие функции держат состояние в Composition.
 * У вьюеров это принципиально, потому что ViewModel привязала бы время жизни декодеров к экрану,
 * а не к показываемому изображению.
 *
 * Достают зависимости через [rememberPresentationEntryPoint].
 * В превью этот вызов падает, потому что рендерер подставляет layoutlib-контекст вместо Application,
 * так что композабл, который сюда ходит, обязан отдельно обрабатывать [LocalInspectionMode].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PresentationEntryPoint {

    fun tiledImageViewerStateFactory(): TiledImageViewerStateFactory

    fun decodeFullImageUseCase(): IDecodeFullImageUseCase

    fun clampPanOffsetUseCase(): IClampPanOffsetUseCase

    fun observeDeviceMetricsUseCase(): IObserveDeviceMetricsUseCase
}
