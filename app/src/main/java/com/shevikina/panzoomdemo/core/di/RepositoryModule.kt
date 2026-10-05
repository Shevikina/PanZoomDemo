package com.shevikina.panzoomdemo.core.di

import com.shevikina.panzoomdemo.data.repository.ImageSourceRepository
import com.shevikina.panzoomdemo.data.repository.MetricsRepository
import com.shevikina.panzoomdemo.domain.repository.IImageSourceRepository
import com.shevikina.panzoomdemo.domain.repository.IMetricsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 *  Репозитории обеспечивают доступ [domain] к [data]
 **/
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindImageSourceRepository(impl: ImageSourceRepository): IImageSourceRepository

    /**
     * Без @Singleton: поток метрик холодный, счётчик джанка живёт в синглтон [JankLocalDataSource]
     **/
    @Binds
    abstract fun bindMetricsRepository(impl: MetricsRepository): IMetricsRepository
}
