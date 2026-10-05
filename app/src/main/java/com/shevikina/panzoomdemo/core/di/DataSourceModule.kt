package com.shevikina.panzoomdemo.core.di

import com.shevikina.panzoomdemo.data.datasource.IImageAssetLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.IJankLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.IMemoryLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.ImageAssetLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.JankLocalDataSource
import com.shevikina.panzoomdemo.data.datasource.MemoryLocalDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Источники данных без собственного жизненного цикла.
 * Пул буферов и декодер создаются фабрикой репозитория: они живут столько же,
 * сколько открытое изображение.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataSourceModule {

    @Binds
    @Singleton
    abstract fun bindImageAssetLocalDataSource(impl: ImageAssetLocalDataSource): IImageAssetLocalDataSource

    @Binds
    @Singleton
    abstract fun bindMemoryLocalDataSource(impl: MemoryLocalDataSource): IMemoryLocalDataSource

    @Binds
    @Singleton
    abstract fun bindJankLocalDataSource(impl: JankLocalDataSource): IJankLocalDataSource
}
