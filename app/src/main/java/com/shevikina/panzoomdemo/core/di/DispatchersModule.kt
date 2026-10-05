package com.shevikina.panzoomdemo.core.di

import com.shevikina.panzoomdemo.core.coroutines.CoroutineDispatchers
import com.shevikina.panzoomdemo.core.coroutines.ICoroutineDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Подмена глобальных `Dispatchers` на абстракцию,
 * чтобы в тестах можно было вместо неё использовать [TestCoroutineDispatchers]
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DispatchersModule {

    @Binds
    @Singleton
    abstract fun bindCoroutineDispatchers(impl: CoroutineDispatchers): ICoroutineDispatchers
}
